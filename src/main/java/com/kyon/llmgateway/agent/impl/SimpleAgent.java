package com.kyon.llmgateway.agent.impl;

import com.kyon.llmgateway.agent.*;
import com.kyon.llmgateway.agent.engine.ToolEngine;
import com.kyon.llmgateway.agent.engine.ToolResult;
import com.kyon.llmgateway.agent.session.SessionManager;
import com.kyon.llmgateway.agent.tool.ToolRegistry;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.model.ToolDefinition;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SessionManager 管理 （持久化到 SQLite、支持 SessionId隔离）
 * 把 Tool Loop 从 CharController 抽出来，加上 Session 管理，变成可独立复用的 Agent \
 * 之前 Tool Loop 写在 Controller 中，和 Http 强耦合，现在 SimpleAgent 是一个纯 Spring Bean，不依赖 HTTP，任何地方都能调用。
 */
@Component
public class SimpleAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(SimpleAgent.class);

    // SimpleAgent 自己不做底层的事，全是调用已有的组件，这就是 组合模式
    @Resource
    private LLMServiceFactory llmServiceFactory;    // 调用 LLM
    @Resource
    private ToolEngine toolEngine;                  // 执行工具
    @Resource
    private ToolRegistry toolRegistry;              // 管理工具定义
    @Resource
    private SessionManager sessionManager;          // 管理对话历史

    @Override
    public AgentResponse chat(AgentRequest request) throws Exception {
        // 1. 确保 sessionId 存在
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(sessionManager.newSessionId());
        }
        String sessionId = request.getSessionId();

        // 2. 追加用户消息到 Session
        sessionManager.append(sessionId, new Message("user", request.getMessage(), null, null, null));

        // 3. 构建工具定义
        List<ToolDefinition> tools = toolRegistry.getAllTools().stream()
                .map(t -> new ToolDefinition("function",
                        new ToolDefinition.FunctionDef(
                                t.getName(),
                                t.getDescription(),
                                t.getParameters()
                        ))).toList();

        // 4. Tool Loop
        int maxToolCalls = 10;
        int callCount = 0;
        ChatResponse lastResponse = null;
        List<ToolCallRecord> toolHistory = new ArrayList<>();

        while (callCount < maxToolCalls) {
            // 获取会话历史（自动剪裁）
            List<Message> history = sessionManager.getHistory(sessionId);

            // 调 LLM
            LLMService service = llmServiceFactory.getLlmService(request.getModel());
            lastResponse = service.chat(history, tools);

            if (lastResponse == null || !lastResponse.hasToolCalls()) {
                break;
            }

            // 追加 assistant tool_calls 消息
            sessionManager.append(sessionId,
                    new Message("assistant", lastResponse.getContent(), lastResponse.getToolCalls(), null, null));

            // 执行工具
            List<ToolResult> results = toolEngine.execute(lastResponse.getToolCalls());
            for (ToolResult r : results) {
                sessionManager.append(sessionId,
                        new Message("tool", r.getContent(), null, r.getToolCallId(), r.getToolName()));

                // 构建记录
                ToolCall tc = new ToolCall();
                tc.setId(r.getToolCallId());
                tc.setName(r.getToolName());
                // arguments 没有，先 null
                toolHistory.add(new ToolCallRecord(tc, r.getContent()));
            }
            callCount++;
        }

        // 5. 追加最终回答
        String finalContent = (lastResponse != null && lastResponse.getContent() != null) ?
                lastResponse.getContent() : "已达到最大工具调用次数限制";
        sessionManager.append(sessionId,
                new Message("assistant", finalContent, null, null, null));

        // 6. 返回
        return AgentResponse.builder()
                .content(finalContent)
                .sessionId(sessionId)
                .toolHistory(toolHistory)
                .inputTokens(lastResponse != null ? lastResponse.getInputTokens() : 0)
                .outputTokens(lastResponse != null ? lastResponse.getOutputTokens() : 0)
                .build();
    }

    @Override
    public void stream(AgentRequest request, Consumer<StreamEvent> emitter) {
        try {
            // 1. 确保 sessionId
            if (request.getSessionId() == null || request.getSessionId().isBlank()) {
                request.setSessionId(sessionManager.newSessionId());
            }
            String sessionId = request.getSessionId();

            // 2. 追加用户消息
            sessionManager.append(sessionId, new Message("user", request.getMessage(), null, null, null));

            // 3. 构建工具定义
            List<ToolDefinition> tools = toolRegistry.getAllTools().stream()
                    .map(t -> new ToolDefinition("function",
                            new ToolDefinition.FunctionDef(
                                    t.getName(),
                                    t.getDescription(),
                                    t.getParameters()
                            )
                    )).toList();

            // 4. 流式 Tool Loop
            int maxToolCalls = 10;
            int callCount = 0;
            ChatResponse lastResponse = null;

            while (callCount < maxToolCalls) {
                List<Message> history = sessionManager.getHistory(sessionId);

                LLMService service = llmServiceFactory.getLlmService(request.getModel());

                // 用 chatStreaming 替代 chat，delta 通过StreamEvent 推出去
                lastResponse = service.chatStreaming(
                        history,
                        tools,
                        delta -> emitter.accept(StreamEvent.content(delta))
                );
                if (lastResponse == null || !lastResponse.hasToolCalls()) {
                    break;
                }

                // 推送 tool_call 事件
                emitter.accept(StreamEvent.toolCall(lastResponse.getToolCalls().asString()));

                // 追加 assistant 消息
                sessionManager.append(sessionId,
                        new Message("assistant", lastResponse.getContent(), lastResponse.getToolCalls(), null, null));

                // 执行工具
                List<ToolResult> results = toolEngine.execute(lastResponse.getToolCalls());
                for (ToolResult r : results) {
                    sessionManager.append(sessionId,
                            new Message("tool", r.getContent(), null, r.getToolCallId(), r.getToolName()));

                    // 推送 tool_result 事件
                    emitter.accept(StreamEvent.toolResult(
                            "{\"name\":\"%s\",\"result\":\"%s\"}".formatted(r.getToolName(), r.getContent())
                    ));
                }
                callCount++;
            }
            // 5. 追加最终回答
            String finalContent = (lastResponse != null && lastResponse.getContent() != null) ?
                    lastResponse.getContent() : "已达到最大工具调用次数限制";
            sessionManager.append(sessionId,
                    new Message("assistant", finalContent, null, null, null));

            // 6. 推送 done 事件
            emitter.accept(StreamEvent.done());
        } catch (Exception e) {
            log.error("Stream 处理失败", e);
            emitter.accept(StreamEvent.error(e.getMessage()));
        }
    }
}
