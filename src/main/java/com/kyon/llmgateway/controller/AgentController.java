package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.agent.AgentRequest;
import com.kyon.llmgateway.agent.AgentResponse;
import com.kyon.llmgateway.agent.StreamEvent;
import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.agent.engine.TokenCounter;
import com.kyon.llmgateway.agent.impl.ReActAgent;
import com.kyon.llmgateway.agent.impl.SimpleAgent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 原生接口
 */
@Controller
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Resource
    private ReActAgent reActAgent;
    @Resource
    private SimpleAgent simpleAgent;
    @Resource
    private SessionManager sessionManager;
    @Resource
    private ContextManager contextManager;
    @Resource
    private LLMServiceFactory llmServiceFactory;

    @Value("${llm.compact-secret}")
    private String compactSecret;

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
        // 客户端断开标记：首次断开后不再推送、不再重复记日志
        AtomicBoolean disconnected = new AtomicBoolean(false);

        // 异步执行，避免阻塞 HTTP 线程
        streamExecutor.submit(() -> {
            try {
                if (request.getMode() != null) {
                    reActAgent.stream(request, event -> sendEvent(emitter, event, disconnected));
                } else {
                    simpleAgent.stream(request, event -> sendEvent(emitter, event, disconnected));
                }
                emitter.complete();
            } catch (Exception e) {
                if (disconnected.get()) {
                    // 客户端已走：正常收尾，不记 ERROR、不再派发异常
                    log.debug("SSE 客户端断开，流提前结束：{}", rootMessage(e));
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                    }
                } else {
                    log.error("Stream 处理失败", e);
                    emitter.completeWithError(e);
                }
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
        if (!compactSecret.equals(request.getSecret())) {
            return ApiResult.error(ResultCode.UNAUTHORIZED, "未授权");
        }

        List<Message> history = sessionManager.getOrCreate(request.getSessionId());
        List<Message> messages = new ArrayList<>(history);

        int originalTokens = TokenCounter.estimateMessages(messages);
        messages = contextManager.summarize(messages, request.getModel(), llmServiceFactory);
        int compressedTokens = TokenCounter.estimateMessages(messages);

        // 用压缩后的消息替换会话历史
        sessionManager.clear(request.getSessionId());
        for (Message m : messages) {
            sessionManager.append(request.getSessionId(), m);
        }

        Map<String, Object> result = Map.of(
                "originalTokens", originalTokens,
                "compressedTokens", compressedTokens,
                "compressionRatio", String.format("%.1f%%", ((1.0 - (double) compressedTokens / originalTokens) * 100)),
                "messageCount", messages.size()
        );

        // 清理 ReActAgent 里的 session 状态
        reActAgent.clearSessionState(request.getSessionId());

        return ApiResult.success(result);
    }

    /**
     * SSE 事件发送（抽取公共逻辑）
     */
    private void sendEvent(SseEmitter emitter, StreamEvent event, AtomicBoolean disconnected) {
        if (disconnected.get()) return; // 已断开：静默丢弃后续事件
        try {
            // done 事件 data 是 null，用空字符串代替
            String data = event.getData() != null ? event.getData() :
                    event.getRawContent() != null ? event.getRawContent() : "";
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(data));
        } catch (Exception e) {
            // 客户端断开时 send 抛异常 -- 正常终止走 complete();
            // 原来断 completeWithError 会触发异常再派发、撞上 text/event-stream 的JSON 二次故障
            disconnected.set(true);   // 置位
            log.debug("SSE 推送失败（客户端断开），停止推送：{}", rootMessage(e));  // 取根因
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 已完成/已错误。忽略
            }
        }
    }

    /** 取异常根因的简短描述：SSE 场景异常链层层嵌套，完整 message 可达数千字符 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
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
        private String model;
    }
}
