package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.agent.Agent;
import com.kyon.llmgateway.agent.AgentRequest;
import com.kyon.llmgateway.agent.AgentResponse;
import com.kyon.llmgateway.model.ApiResult;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Resource
    private Agent agent;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步对话
     */
    @PostMapping("/chat")
    @ResponseBody
    public ApiResult<AgentResponse> chat(@RequestBody AgentRequest request) throws Exception {
        AgentResponse response = agent.chat(request);
        return ApiResult.success(response);
    }

    /**
     * 流式对话
     * 这里直接把 Agent 的 StreamEvent 通过 SSE 推给前端，前端根据事件类型 content / tool_call / tool_result / done / error 分别处理
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        // 异步执行，避免阻塞 HTTP 线程
        streamExecutor.submit(() -> {
            try {
                agent.stream(request, event -> {
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
                });
                emitter.complete();
            } catch (Exception e) {
                log.error("Stream 处理失败", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
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
}
