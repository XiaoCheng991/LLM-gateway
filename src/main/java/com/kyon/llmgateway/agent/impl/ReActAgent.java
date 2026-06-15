package com.kyon.llmgateway.agent.impl;

import com.kyon.llmgateway.agent.*;
import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.agent.engine.TokenCounter;
import com.kyon.llmgateway.agent.engine.ToolEngine;
import com.kyon.llmgateway.agent.engine.ToolResult;
import com.kyon.llmgateway.agent.session.SessionManager;
import com.kyon.llmgateway.agent.state.AgentState;
import com.kyon.llmgateway.agent.tool.ToolRegistry;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.model.ToolDefinition;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReActAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper om = new ObjectMapper();

    private static final int MAX_ITERATIONS = 15;
    private static final int DEFAULT_CONTEXT_WINDOW = 32000;
    private static final int MAX_THOUGHT_LENGTH = 2000;
    private static final double WARN_THRESHOLD = 0.85;

    // 匹配 <thinking>...</thinking> 标签 (D-01)
    private static final Pattern THOUGHT_PATTERN = Pattern.compile("<thinking>([\\s\\S]*?)</thinking>", Pattern.DOTALL);

    // 每个 session 独立状态（ConcurrentHashMap 保证线程安全）
    private final ConcurrentHashMap<String, AgentState> sessionStates = new ConcurrentHashMap<>();

    @Resource
    private LLMServiceFactory llmServiceFactory;
    @Resource
    private ToolEngine toolEngine;
    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private SessionManager sessionManager;
    @Resource
    private ContextManager contextManager;


    // ============== Agent 接口实现 =================
    @Override
    public AgentResponse chat(AgentRequest request) throws Exception {
        // 同步模式：走流式 LLM 调用，直接返回最终结果
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(sessionManager.newSessionId());
        }
        String sessionId = request.getSessionId();

        // 追加用户消息
        sessionManager.append(sessionId, new Message("user", request.getMessage(), null, null, null));

        // 上下文压缩
        List<Message> messages = sessionManager.getHistory(sessionId);
        messages = contextManager.truncateToThreshold(messages, llmServiceFactory);

        // 同步 ReAct 循环
        AgentResponse response;
        if (request.getMode() == AgentMode.PLAN_EXECUTE) {
            response = executePlanModeSync(messages, request);
        } else {
            response = executeReactModeSync(messages, request);
        }
        return response;
    }

    // 同步 React 循环
    private AgentResponse executeReactModeSync(List<Message> messages, AgentRequest request) throws Exception {
        String sessionId = request.getSessionId();
        transitionToSilent(sessionId, AgentState.THINKING);

        List<ToolDefinition> tools = buildToolDefinitions();
        List<ToolCallRecord> toolHistory = new ArrayList<>();
        String finalContent = "";

        int initialSize = messages.size();
        int iterations = 0;

        while (iterations < MAX_ITERATIONS) {
            checkTokenWarning(messages);

            // 重点：调用 chat() 方法
            LLMService service = llmServiceFactory.getLlmService(request.getModel());
            ChatResponse response = service.chat(messages, tools);

            if (response == null || !response.hasToolCalls()) {
                finalContent = (response != null && response.getContent() != null)
                        ? response.getContent()
                        : "处理完成，但是未生成文本回复";
                break;
            }

            transitionToSilent(sessionId, AgentState.ACTING);

            // 追加 assistant 消息
            Message assistantMsg = new Message("assistant", response.getContent(), response.getToolCalls(), null, null);
            messages.add(assistantMsg);

            // 执行工具
            List<ToolResult> results = toolEngine.execute(response.getToolCalls());
            for (ToolResult r : results) {
                String wrapped = "[TOOL_RESULT: name=" + r.getToolName() + " content=" +
                        r.getContent().replace("]", "\\]") + "]";
                Message toolMsg = new Message("tool", wrapped, null, r.getToolCallId(), r.getToolName());
                messages.add(toolMsg);

                toolHistory.add(new ToolCallRecord(
                        new ToolCall() {{
                            setId(r.getToolCallId());
                            setName(r.getToolName());
                        }}, r.getContent()));
            }

            transitionToSilent(sessionId, AgentState.THINKING);
            iterations++;
        }

        if (iterations >= MAX_ITERATIONS) {
            log.warn("ReAct 达到最大迭代次数 {}", MAX_ITERATIONS);
            transitionToSilent(sessionId, AgentState.ERROR);
            finalContent = "已达到最大迭代次数限制：" + MAX_ITERATIONS + ", 已保留部分结果";
        } else {
            transitionToSilent(sessionId, AgentState.DONE);
        }

        // 将 ReAct 循环中新增的消息持久化到 session
        for (int i = initialSize; i < messages.size(); i++) {
            sessionManager.append(sessionId, messages.get(i));
        }

        // 追加最终回答
        sessionManager.append(sessionId, new Message("assistant", finalContent, null, null, null));

        return AgentResponse.builder()
                .content(finalContent)
                .sessionId(sessionId)
                .toolHistory(toolHistory)
                .inputTokens(0)
                .outputTokens(0)
                .build();
    }

    private AgentResponse executePlanModeSync(List<Message> messages, AgentRequest request) throws Exception {
        String sessionId = request.getSessionId();
        transitionToSilent(sessionId, AgentState.PLANNING);

        // 1. 让 LLM 生成计划
        String planPrompt = "You need to complete a complex task. First, create a step-by-step plan. " +
                "OutPut a Json array of steps. Each step should be a clear, actionable item." +
                "Use the following format: [{\"step\": 1, \"description\": \"...\"}, ...]\n\n" +
                "Task: " + request.getMessage();

        List<Message> planMessages = new ArrayList<>();
        planMessages.add(new Message("user", planPrompt, null, null, null));

        LLMService service = llmServiceFactory.getLlmService(request.getModel());
        ChatResponse planResponse = service.chat(planMessages, null);
        String planJson = planResponse != null ? planResponse.getContent() : null;

        if (planJson == null || planJson.isBlank()) {
            return executeReactModeSync(messages, request);
        }

        // 2. 解析计划 Json
        JsonNode planNode;
        try {
            planNode = getJsonNode(planJson);
        } catch (Exception e) {
            return executeReactModeSync(messages, request);
        }

        // 3. 逐步执行（D-10: 每步一个完整 ReAct 子循环）
        List<ToolCallRecord> allHistory = new ArrayList<>();
        StringBuilder finalContent = new StringBuilder();
        for (JsonNode step : planNode) {
            String desc = step.path("description").asString("");
            if (desc.isBlank()) continue;

            // 构造子请求
            AgentRequest sub = cloneRequest(request, "Execute this step: " + desc + "\nContext: " + request.getMessage());

            // 每个子步骤用独立的消息副本，互不干扰
            List<Message> stepMessages = new ArrayList<>(messages);
            AgentResponse stepResp = executeReactModeSync(stepMessages, sub);

            allHistory.addAll(stepResp.getToolHistory());
            finalContent.append(stepResp.getContent()).append("\n");
        }

        transitionToSilent(sessionId, AgentState.DONE);
        sessionManager.append(sessionId, new Message("assistant", finalContent.toString(), null, null, null));

        return AgentResponse.builder()
                .content(finalContent.toString())
                .sessionId(sessionId)
                .toolHistory(allHistory)
                .inputTokens(0)
                .outputTokens(0)
                .build();
    }

    /**
     * 获取 JsonNode 节点
     */
    @NonNull
    private JsonNode getJsonNode(String planJson) {
        JsonNode planNode;
        String cleaned = planJson.replace("```json", "").replace("```", "").trim();
        if (cleaned.isBlank()) {
            throw new RuntimeException("清理后的计划 JSON 为空");
        }
        planNode = om.readTree(cleaned);
        if (!planNode.isArray() || planNode.isEmpty()) {
            throw new RuntimeException("计划不是非空数组");
        }
        return planNode;
    }

    @Override
    public void stream(AgentRequest request, Consumer<StreamEvent> emitter) {
        try {
            chatStreaming(request, emitter);
        } catch (Exception e) {
            log.error("ReAct stream 失败", e);
            try {
                transitionTo(request.getSessionId(), AgentState.ERROR, emitter);
                emitter.accept(StreamEvent.error(e.getMessage()));
            } catch (Exception ex) {
                log.error("发送 error 事件失败", ex);
            }
        }
    }

    // =========== 核心调度 =============
    private void chatStreaming(AgentRequest request, Consumer<StreamEvent> emitter) throws Exception {
        // 1. 确保 sessionId
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(sessionManager.newSessionId());
        }
        String sessionId = request.getSessionId();

        // 2. 追加用户消息
        sessionManager.append(sessionId, new Message("user", request.getMessage(), null, null, null));

        // 3. 获取历史 + 上下文压缩 (D-13/D-15: 在ReAct 循环前)
        List<Message> messages = sessionManager.getHistory(sessionId);
        messages = contextManager.truncateToThreshold(messages, llmServiceFactory);

        // 4. 分支：REACT or PLAN_EXECUTE
        if (request.getMode() == AgentMode.PLAN_EXECUTE) {
            executePlanMode(messages, request, emitter);
        } else {
            executeReactMode(messages, request, emitter);
        }
    }

    // ============ REACT 模式 =============
    private void executeReactMode(List<Message> messages, AgentRequest request, Consumer<StreamEvent> emitter) throws Exception {
        String sessionId = request.getSessionId();
        transitionTo(sessionId, AgentState.THINKING, emitter);

        List<ToolDefinition> tools = buildToolDefinitions();
        String fullResponse;

        int initialSize = messages.size();
        int iterations = 0;

        while (iterations < MAX_ITERATIONS) {
            // 检查 token 预警 (Pitfall 2: 85% 告警)
            checkTokenWarning(messages);

            // 调 LLM (流式, delta 回调处理思考内容提取
            StringBuilder thoughtBuffer = new StringBuilder();
            StringBuilder contentBuffer = new StringBuilder();
            boolean[] inThought = {false};

            LLMService service = llmServiceFactory.getLlmService(request.getModel());
            ChatResponse response = service.chatStreaming(
                    messages, tools,
                    delta -> extractThoughtAndStream(delta, thoughtBuffer, contentBuffer, inThought, emitter)
            );
            fullResponse = response != null ? response.getContent() : "";

            if (response == null || !response.hasToolCalls()) {
                // 无工具调用 -> 最终回答
                if (!contentBuffer.isEmpty()) {
                    emitter.accept(StreamEvent.content(contentBuffer.toString()));
                }
                break;
            }

            // 追加 assistant 消息
            Message assistantMsg = new Message("assistant", fullResponse,
                    response.getToolCalls(), null, null);
            messages.add(assistantMsg);

            // 推送 tool_call 事件
            emitter.accept(StreamEvent.toolCall(response.getToolCalls().toString()));

            // 执行工具
            List<ToolResult> results = toolEngine.execute(response.getToolCalls());
            for (ToolResult r : results) {
                // 包装工具结果防注入 (Pitfall 6)
                String wrapped = "[TOOL_RESULT: name=" + r.getToolName() + " content=" +
                        r.getContent().replace("]", "\\]") + "]";
                Message toolMsg = new Message("tool", wrapped, null, r.getToolCallId(), r.getToolName());
                messages.add(toolMsg);

                Map<String, String> toolResult = Map.of("name", r.getToolName(), "result", r.getContent());
                emitter.accept(StreamEvent.toolResult(om.writeValueAsString(toolResult)));
            }

            // 回到 THINKING
            transitionTo(sessionId, AgentState.THINKING, emitter);
            iterations++;
        }

        if (iterations >= MAX_ITERATIONS) {
            log.warn("ReAct 达到最大迭代次数 {}", MAX_ITERATIONS);

            // 将循环中已累计的消息持久化到 session
            for (int i = initialSize; i < messages.size(); i++) {
                sessionManager.append(sessionId, messages.get(i));
            }
            transitionTo(sessionId, AgentState.ERROR, emitter);
            emitter.accept(StreamEvent.error("达到最大迭代次数限制：" + MAX_ITERATIONS));
            return;
        }

        // 将ReAct 循环中新增的消息持久化到 session
        for (int i = initialSize; i < messages.size(); i++) {
            sessionManager.append(sessionId, messages.get(i));
        }

        // DONE
        transitionTo(sessionId, AgentState.DONE, emitter);
        emitter.accept(StreamEvent.done());
    }


    // =========== PLAN_EXECUTE 模式 ==============
    private void executePlanMode(List<Message> messages, AgentRequest request,
                                 Consumer<StreamEvent> emitter) throws Exception {
        String sessionId = request.getSessionId();
        transitionTo(sessionId, AgentState.PLANNING, emitter);


        // 1. 让 LLM 生成计划
        String planPrompt = "You need to complete a complex task. First, create a step-by-step plan to solve the user's request. " +
                "OutPut a Json array of state. Each step should be a clear, actionable item." +
                "Use the following format: [{\"step\": 1, \"description\": \"...\"}, ...]\n\n" +
                "Task: " + request.getMessage();

        List<Message> planMessages = new ArrayList<>(messages);
        planMessages.add(new Message("system", planPrompt, null, null, null));

        LLMService service = llmServiceFactory.getLlmService(request.getModel());
        ChatResponse planResponse = service.chat(planMessages, null);
        String planJson = planResponse != null ? planResponse.getContent() : null;

        if (planJson == null || planJson.isBlank()) {
            log.warn("计划生成失败, 回退到 REACT 模式");
            executeReactMode(messages, request, emitter);
        }

        // 2. 推计划给前端
        emitter.accept(StreamEvent.plan(planJson));

        // 3. 解析计划 Json
        JsonNode planNode;
        try {
            // 尝试提取 JSON 数据（LLM 可能包裹在 ```json```中）
            if (planJson == null || planJson.isBlank()) {
                throw new RuntimeException("计划 JSON 为空");
            }
            planNode = getJsonNode(planJson);
        } catch (Exception e) {
            log.warn("计划 JSON 解析失败，回退到 REACT 模式: {}", e.getMessage());
            executeReactMode(messages, request, emitter);
            return;
        }

        // 4. 逐步执行（D-10: 每步一个完整 ReAct 子循环）
        for (JsonNode stepNode : planNode) {
            String description = stepNode.path("description").asString("");
            if (description.isBlank()) continue;

            log.info("执行计划步骤: {}", description);

            // 构造子请求
            AgentRequest subRequest = new AgentRequest();
            subRequest.setSessionId(sessionId);
            subRequest.setModel(request.getModel());
            subRequest.setMode(AgentMode.REACT);
            subRequest.setMessage("Execute this step: " + description + "\n\nOriginal task context: " + request.getMessage());

            // 每个子步骤用独立的消息副本，互不干扰
            List<Message> stepMessages = new ArrayList<>(messages);

            // 子循环用同一个 messages 和 emitter
            executeReactMode(stepMessages, subRequest, emitter);
        }

        transitionTo(sessionId, AgentState.DONE, emitter);
        emitter.accept(StreamEvent.done());
    }

    private static final ThreadLocal<Matcher> TL_MATCHER = ThreadLocal.withInitial(() -> THOUGHT_PATTERN.matcher(""));

    // ============== 辅助方法 ================
    /**
     * 思考内容提取（D-01/D-02）
     * 从流式 delta 中识别 <thinking>...</thinking>, 分别推送 THINKING 和 CONTENT 事件
     */
    private void extractThoughtAndStream(String delta, StringBuilder thoughtBuffer, StringBuilder contentBuffer,
                                         boolean[] inThought, Consumer<StreamEvent> emitter) {

        // 简单实现： 累积到 thoughBuffer, 用正则匹配
        thoughtBuffer.append(delta);
        String text = thoughtBuffer.toString();

        Matcher matcher = TL_MATCHER.get().reset(text);
        if (matcher.find()) {
            String thought = matcher.group(1).trim();
            // 截断超长思考（Pitfall 3）
            if (thought.length() > MAX_THOUGHT_LENGTH) {
                thought = thought.substring(0, MAX_THOUGHT_LENGTH) + "...[truncated]";
            }
            emitter.accept(StreamEvent.thinking(thought));
            // 清空已处理部分
            thoughtBuffer.delete(0, matcher.end());
            inThought[0] = false;
        }

        // 非思考内容推为 content
        if (!inThought[0] && contentBuffer != null) {
            // 检查是否有 <thinking> 开始标签
            int thinkStart = text.indexOf("<thinking>");
            if (thinkStart >= 0) {
                // 标签前的内容推为 conten
                if (thinkStart > 0) {
                    emitter.accept(StreamEvent.content(text.substring(0, thinkStart)));
                }
                inThought[0] = true;
                thoughtBuffer.delete(0, thoughtBuffer.length());
                thoughtBuffer.append(text.substring(thinkStart));
            } else {
                emitter.accept(StreamEvent.content(delta));
            }
        }
    }

    /**
     * 同步状态转换（不发 SSE 事件，只更新 currentState）
     */
    private void transitionToSilent(String sessionId, AgentState next) {
        // 避免并发请求同一 session 状态机死锁
        sessionStates.compute(sessionId, (k, prev) -> {
           if (prev == null) prev = AgentState.IDLE;
           try {
               prev.transitionTo(next);
           } catch (IllegalStateException e) {
               log.warn("非法状态转换: {} -> {}. 错误: {}", prev, next, e.getMessage());
               return next;
           }
           return next;
        });
    }

    /**
     * 清理 session 状态（session 清除时调用）
     */
    public void clearSessionState(String sessionId) {
        sessionStates.remove(sessionId);
    }

    /**
     * 克隆请求用于子步骤
     */
    private AgentRequest cloneRequest(AgentRequest src, String newMessage) {
        AgentRequest r = new AgentRequest();
        r.setSessionId(src.getSessionId());
        r.setModel(src.getModel());
        r.setMode(src.getMode());
        r.setMessage(newMessage);
        return r;
    }

    /**
     * 状态转换 + 推送 STATE 事件
     */
    private void transitionTo(String sessionId, AgentState next, Consumer<StreamEvent> emitter) {
        // 避免并发请求同一 session 状态机死锁
        sessionStates.compute(sessionId, (k, prev) -> {
            if (prev == null) prev = AgentState.IDLE;
            try {
                prev.transitionTo(next);
            } catch (IllegalStateException e) {
                log.warn("非法状态转换: {} -> {}. 错误: {}", prev, next, e.getMessage());
                return next;
            }
            return next;
        });
        emitter.accept(StreamEvent.state(next.name()));
        log.debug("状态转换 -> {}", next);
    }

    /**
     * 构建工具定义列表
     */
    private List<ToolDefinition> buildToolDefinitions() {
        return toolRegistry.getAllTools().stream()
                .map(t -> new ToolDefinition("function",
                        new ToolDefinition.FunctionDef(
                                t.getName(),
                                t.getDescription(),
                                t.getParameters()
                        ))).toList();
    }

    /**
     * Token 预警 (Pitfall 2: 85% 打 warn 日志)
     */
    private void checkTokenWarning(List<Message> messages) {
        int tokens = TokenCounter.estimateMessages(messages);
        int warnTokens = (int) (DEFAULT_CONTEXT_WINDOW * WARN_THRESHOLD);
        if (tokens > warnTokens) {
            log.warn("上下文 token 接近上限: {}/{} ({}%)", tokens, DEFAULT_CONTEXT_WINDOW, tokens*100/DEFAULT_CONTEXT_WINDOW);
        }
    }

}
