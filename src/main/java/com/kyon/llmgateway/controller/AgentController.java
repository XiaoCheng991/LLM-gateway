package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.agent.Agent;
import com.kyon.llmgateway.agent.AgentRequest;
import com.kyon.llmgateway.agent.AgentResponse;
import com.kyon.llmgateway.agent.StreamEvent;
import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.agent.engine.TokenCounter;
import com.kyon.llmgateway.agent.impl.ReActAgent;
import com.kyon.llmgateway.agent.session.SessionManager;
import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.model.enums.ResultCode;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Resource
    private ReActAgent reActAgent;
    @Resource
    private Agent simpleAgent;
    @Resource
    private SessionManager sessionManager;
    @Resource
    private ContextManager contextManager;
    @Resource
    private LLMServiceFactory llmServiceFactory;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步对话
     * mode=react | plan_execute -> ReActAgent, 其他/null -> SimpleAgent（向后兼容）
     */
    @PostMapping("/chat")
    @ResponseBody
    public ApiResult<AgentResponse> chat(@RequestBody AgentRequest request) throws Exception {
        AgentResponse response;

        // 根据是否有 mode 来决定是否采用 ReAct
        if (request.getMode() != null) {
            response = reActAgent.chat(request);
        } else {
            response = simpleAgent.chat(request);
        }
        return ApiResult.success(response);
    }

    /**
     * 流式对话
     * mode=react | plan_execute -> ReActAgent, 其他/null -> SimpleAgent（向后兼容）
     * 这里直接把 Agent 的 StreamEvent 通过 SSE 推给前端，前端根据事件类型 content / tool_call / tool_result / done / error 分别处理
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        // 异步执行，避免阻塞 HTTP 线程
        streamExecutor.submit(() -> {
            try {
                if (request.getMode() != null) {
                    reActAgent.stream(request, event -> sendEvent(emitter, event));
                } else {
                    simpleAgent.stream(request, event -> sendEvent(emitter, event));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("Stream 处理失败", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 手动触发上下文压缩
     */
    @PostMapping("/compact")
    @ResponseBody
    public ApiResult<Map<String, Object>> compact(@RequestBody CompactRequest request) throws Exception {
        // 简单 secret 校验（生产环境应该换成 JWT/Session 鉴权）
        if (!"llm-gateway-compact-secret".equals(request.getSecret())) {
            return ApiResult.error(ResultCode.UNAUTHORIZED, "未授权");
        }

        List<Message> history = sessionManager.getOrCreate(request.getSessionId());
        List<Message> messages = new ArrayList<>(history);

        int originalTokens = TokenCounter.estimateMessages(messages);
        messages = contextManager.summarize(messages, 32000, llmServiceFactory);
        int compressedTokens = TokenCounter.estimateMessages(messages);

        // 用压缩后的消息替换会话历史
        sessionManager.clear(request.getSessionId());
        for (Message m : messages) {
            sessionManager.append(request.getSessionId(), m);
        }

        Map<String, Object> result = Map.of(
                "originalTokens", originalTokens,
                "compressedTokens", compressedTokens,
                "compressionRatio", String.format("%.1f%%", (1.0 - (double) compressedTokens / originalTokens * 100)),
                "messageCount", messages.size()
        );

        // 清理 ReActAgent 里的 session 状态
        reActAgent.clearSessionState(request.getSessionId());

        return ApiResult.success(result);
    }

    /**
     * SSE 事件发送（抽取公共逻辑）
     */
    private void sendEvent(SseEmitter emitter, StreamEvent event) {
        try {
            // done 事件 data 是 null，用空字符串代替
            String data = event.getData() != null ? event.getData() :
                    event.getRawContent() != null ? event.getRawContent() : "";
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdown();
    }

    /**
     * 返回前端页面
     */
    @GetMapping("/chat")
    public String chatPage() {
        return "agent-chat";
    }

    /**
     * /compact 请求体
     */
    @Getter
    @Setter
    public static class CompactRequest {
        private String sessionId;
        private String secret;
    }
}
