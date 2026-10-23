package com.kyon.llmgateway.agent.impl;

import com.kyon.llmgateway.agent.*;
import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.agent.engine.ThoughtTags;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class ReActAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper om = new ObjectMapper();

    private static final int MAX_ITERATIONS = 15;
    private static final int MAX_THOUGHT_LENGTH = 2000;
    private static final double WARN_THRESHOLD = 0.85;

    @Value("${llm.default-context-window}")
    private int contextWindow;

    // thinking 标签常量
    private static final String OPEN_TAG = ThoughtTags.OPEN;
    private static final String CLOSE_TAG = ThoughtTags.CLOSE;

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
        // 1. 确保 sessionId 存在
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(sessionManager.newSessionId());
        }
        String sessionId = request.getSessionId();

        // 2. 追加用户消息
        sessionManager.append(sessionId, new Message("user", request.getMessage(), null, null, null));

        // 上下文压缩
        List<Message> messages = sessionManager.getHistory(sessionId);
        messages = contextManager.truncateToThreshold(messages, request.getModel(), llmServiceFactory);

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
        String model = request.getModel();
        transitionTo(sessionId, AgentState.THINKING, null);

        List<ToolDefinition> tools = buildToolDefinitions();
        List<ToolCallRecord> toolHistory = new ArrayList<>();
        String finalContent = "";

        int initialSize = messages.size();
        int iterations = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        while (iterations < MAX_ITERATIONS) {
            checkTokenWarning(messages);

            // 重点：调用 chat() 方法
            LLMService service = llmServiceFactory.getLlmService(request.getModel());
            ChatResponse response = service.chat(model, messages, tools);
            if (response != null) {
                // 多轮工具循环每轮都是一次真实上游调用：用量跨轮累计
                totalInputTokens += response.getInputTokens() != null ? response.getInputTokens() : 0;
                totalOutputTokens += response.getOutputTokens() != null ? response.getOutputTokens() : 0;
            }

            // 思考标签不进历史与最终回答（strip 对 null 原样返回）
            String content = ThoughtTags.strip(response != null ? response.getContent() : null);

            // 出口
            if (response == null || !response.hasToolCalls()) {
                finalContent = (content != null)
                        ? content
                        : "处理完成，但是未生成文本回复";
                break;
            }

            transitionTo(sessionId, AgentState.ACTING, null);

            // 追加 assistant 消息
            Message assistantMsg = new Message("assistant", content, response.getToolCalls(), null, null);
            messages.add(assistantMsg);

            // 执行工具
            List<ToolResult> results = toolEngine.execute(response.getToolCalls());
            for (ToolResult r : results) {

                // 纵深防御，包装 + ] 转义
                String wrapped = "[TOOL_RESULT: name=" + r.getToolName() + " content=" +
                        r.getContent().replace("]", "\\]") + "]";
                Message toolMsg = new Message("tool", wrapped, null, r.getToolCallId(), r.getToolName());
                messages.add(toolMsg);

                // 双大括号初始化
                toolHistory.add(new ToolCallRecord(
                        new ToolCall() {{ setId(r.getToolCallId()); setName(r.getToolName()); }},
                        r.getContent()
                        )
                );
            }

            transitionTo(sessionId, AgentState.THINKING, null);
            iterations++;
        }

        if (iterations >= MAX_ITERATIONS) {
            log.warn("ReAct 达到最大迭代次数 {}", MAX_ITERATIONS);
            transitionTo(sessionId, AgentState.ERROR, null);
            finalContent = "已达到最大迭代次数限制：" + MAX_ITERATIONS + ", 已保留部分结果";
        } else {
            transitionTo(sessionId, AgentState.DONE, null);
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
                .inputTokens(totalInputTokens)
                .outputTokens(totalOutputTokens)
                .build();
    }

    private AgentResponse executePlanModeSync(List<Message> messages, AgentRequest request) throws Exception {
        String sessionId = request.getSessionId();
        String model = request.getModel();
        transitionTo(sessionId, AgentState.PLANNING, null);

        // 1. 让 LLM 生成计划
        String planPrompt = "You need to complete a complex task. First, create a step-by-step plan. " +
                "OutPut a Json array of steps. Each step should be a clear, actionable item." +
                "Use the following format: [{\"step\": 1, \"description\": \"...\"}, ...]\n\n" +
                "Task: " + request.getMessage();

        List<Message> planMessages = new ArrayList<>();
        planMessages.add(new Message("user", planPrompt, null, null, null));

        LLMService service = llmServiceFactory.getLlmService(request.getModel());
        ChatResponse planResponse = service.chat(model, planMessages, null);
        String planJson = planResponse != null ? ThoughtTags.strip(planResponse.getContent()): null;

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
        // 计划生成调用本身也是真实上游调用，一并计入
        int totalInputTokens = planResponse.getInputTokens() != null ? planResponse.getInputTokens() : 0;
        int totalOutputTokens = planResponse.getOutputTokens() != null ? planResponse.getOutputTokens() : 0;

        for (JsonNode step : planNode) {
            String desc = step.path("description").asString("");
            if (desc.isBlank()) continue;

            // 构造子请求
            AgentRequest sub = cloneRequest(request, "Execute this step: " + desc + "\nContext: " + request.getMessage());

            // 每个子步骤用独立的消息副本，互不干扰
            List<Message> stepMessages = new ArrayList<>(messages);
            AgentResponse stepResp = executeReactModeSync(stepMessages, sub);
            // 子循环的用量并入总计（AgentResponse 是 int 原始类型，无需判空）
            totalInputTokens += stepResp.getInputTokens();
            totalOutputTokens += stepResp.getOutputTokens();

            allHistory.addAll(stepResp.getToolHistory());
            finalContent.append(stepResp.getContent()).append("\n");
        }

        transitionTo(sessionId, AgentState.DONE, null);
        sessionManager.append(sessionId, new Message("assistant", finalContent.toString(), null, null, null));

        return AgentResponse.builder()
                .content(finalContent.toString())
                .sessionId(sessionId)
                .toolHistory(allHistory)
                .inputTokens(totalInputTokens)
                .outputTokens(totalOutputTokens)
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
        messages = contextManager.truncateToThreshold(messages, request.getModel(), llmServiceFactory);

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
        String model = request.getModel();
        transitionTo(sessionId, AgentState.THINKING, emitter);

        List<ToolDefinition> tools = buildToolDefinitions();
        String fullResponse = "";

        int initialSize = messages.size();
        int iterations = 0;

        while (iterations < MAX_ITERATIONS) {
            // 检查 token 预警 (Pitfall 2: 85% 告警)
            checkTokenWarning(messages);

            // 调 LLM (流式, delta 回调处理思考内容提取
            StringBuilder thoughtBuffer = new StringBuilder();
            boolean[] inThought = {false};

            LLMService service = llmServiceFactory.getLlmService(request.getModel());
            ChatResponse response = service.chatStreaming(
                    model, messages, tools,
                    delta -> extractThoughtAndStream(delta, thoughtBuffer, inThought, emitter),
                    r -> emitter.accept(StreamEvent.thinking(r))
            );
            fullResponse = response != null ? ThoughtTags.strip(response.getContent()) : "";

            if (response == null || !response.hasToolCalls()) {
                // 缓冲里剩下的只有不完整的标签碎片，不该推
                break;
            }

            // 工具执行前进入 ACTING
            transitionTo(sessionId, AgentState.ACTING, emitter);

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
            log.warn("ReAct 达到最大的迭代次数 {}", MAX_ITERATIONS);

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
        
        // 最终回答落盘
        if (fullResponse != null && !fullResponse.isBlank()) {
            sessionManager.append(sessionId, new Message("assistant", fullResponse, null, null, null));
        }

        // DONE
        transitionTo(sessionId, AgentState.DONE, emitter);
        emitter.accept(StreamEvent.done());
    }


    // =========== PLAN_EXECUTE 模式 ==============
    private void executePlanMode(List<Message> messages, AgentRequest request,
                                 Consumer<StreamEvent> emitter) throws Exception {
        String sessionId = request.getSessionId();
        String model = request.getModel();
        transitionTo(sessionId, AgentState.PLANNING, emitter);


        // 1. 让 LLM 生成计划
        String planPrompt = "You need to complete a complex task. First, create a step-by-step plan to solve the user's request. " +
                "OutPut a Json array of state. Each step should be a clear, actionable item." +
                "Use the following format: [{\"step\": 1, \"description\": \"...\"}, ...]\n\n" +
                "Task: " + request.getMessage();

        List<Message> planMessages = new ArrayList<>(messages);
        planMessages.add(new Message("system", planPrompt, null, null, null));

        LLMService service = llmServiceFactory.getLlmService(request.getModel());
        ChatResponse planResponse = service.chat(model, planMessages, null);
        String planJson = planResponse != null ? ThoughtTags.strip(planResponse.getContent()) : null;

        if (planJson == null || planJson.isBlank()) {
            log.warn("计划生成失败, 回退到 REACT 模式");
            executeReactMode(messages, request, emitter);
            return;
        }

        // 2. 推计划给前端
        emitter.accept(StreamEvent.plan(planJson));

        // 3. 解析计划 Json
        JsonNode planNode;
        try {
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

    // ============== 辅助方法 ================
    /**
     * 思考内容提取（D-01/D-02）
     * <p>
     * 单通道设计：所有输出都从 thoughtBuffer 切出，推送即删除；
     * 缓冲区不可能包含"已推送过的内容"，因此重复推送在结构上不会发生。
     */
    private void extractThoughtAndStream(String delta, StringBuilder thoughtBuffer,
                                         boolean[] inThought, Consumer<StreamEvent> emitter) {

        // 累积缓冲到 thoughBuffer, 交给下面的状态机消化
        thoughtBuffer.append(delta);

        while (true) {
            // 思考态：现在在思考标签内部
            if (inThought[0]) {
                int end = thoughtBuffer.indexOf(CLOSE_TAG);
                if (end < 0) break;  // 思考还没写完，等下一帧过来

                String thought = thoughtBuffer.substring(0, end).trim();
                // 截断超长思考
                if (thought.length() > MAX_THOUGHT_LENGTH) {
                    thought = thought.substring(0, MAX_THOUGHT_LENGTH) + "...[truncated]";
                }
                emitter.accept(StreamEvent.thinking(thought));
                // 清空已处理部分
                thoughtBuffer.delete(0, end + CLOSE_TAG.length());
                inThought[0] = false;
                // 继续循环：闭合标签后面可能紧跟下一个标签或者正文
            } else {
                // 非思考态：标记不在思考标签内部, 找开始标签
                int start = thoughtBuffer.indexOf(OPEN_TAG);
                int stray = thoughtBuffer.indexOf(CLOSE_TAG);

                // 1. start 标签存在，end 标签不存在或者在 start 标签前面
                if (start >= 0 && (stray < 0 || start < stray)) {
                    // 标签前的正文推为content（推过即删，不重复）
                    if (start > 0) {
                        emitter.accept(StreamEvent.content(thoughtBuffer.substring(0, start)));
                        thoughtBuffer.delete(0, start);
                    }
                    thoughtBuffer.delete(0, OPEN_TAG.length()); // 删除开始标签
                    inThought[0] = true;
                }
                // 2. end 标签存在，不管 start 标签在哪
                else if (stray >= 0) {
                    // 游离的 end 标签（没有配对）：跳过，前面的正文照推
                    if (stray > 0) {
                        emitter.accept(StreamEvent.content(thoughtBuffer.substring(0, stray)));
                    }
                    thoughtBuffer.delete(0, stray + CLOSE_TAG.length()); // 删除闭合标签
                }
                // 3. 没有任何标签：正文直接推，但扣住尾部“疑似被拆分的标签前缀”
                else {
                    int safeEnd = safeContentEnd(thoughtBuffer);
                    if (safeEnd > 0) {
                        emitter.accept(StreamEvent.content(thoughtBuffer.substring(0, safeEnd)));
                        thoughtBuffer.delete(0, safeEnd);
                    }
                    return;
                }
            }
        }
    }

    /**
     * 计算缓冲区可安全推送为正文的长度
     * 模型的标签可能被拆成两帧到达（这帧是 "<thin", 下帧是 "king>"）
     * 尾部如果是标签的前缀片段，必须留在缓冲区等下一帧拼完整，否则标签永远识别不出来
     */
    private int safeContentEnd(StringBuilder buf) {
        // 缓冲区只有残缺标签就取标签，如果大于标签长度就说明还有正文，为识别取标签长度-1
        int maxCut = Math.min(buf.length(), CLOSE_TAG.length() - 1);  // 也可以覆盖 start 标签的情况
        for (int k = maxCut; k > 0; k--) {
            // 倒着切，能保证对于"<th"和"<thinki" 先保留长的
            String tail = buf.substring(buf.length() - k);
            if (OPEN_TAG.startsWith(tail) || CLOSE_TAG.startsWith(tail)) {
                return buf.length() - k;
            }
        }
        return buf.length();
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
        if (emitter != null) {
            emitter.accept(StreamEvent.state(next.name()));
            log.debug("状态转换 -> {}", next);
        }
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
        int warnTokens = (int) (contextWindow * WARN_THRESHOLD);
        if (tokens > warnTokens) {
            log.warn("上下文 token 接近上限: {}/{} ({}%)", tokens, contextWindow, tokens*100/contextWindow);
        }
    }

}
