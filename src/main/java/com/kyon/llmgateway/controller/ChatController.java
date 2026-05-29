package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.ChatRequest;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST /api/chat - 统一入口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Resource
    private LLMServiceFactory factory;

    /**
     * 普通交互，仅一次
     * @param request 请求体
     * @return ChatResponse
     */
    @PostMapping("/completions")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            LLMService service = factory.getLlmService(request.getModel());

            // 从 request.getMessages() 中取出 user 消息
            return ApiResult.success(service.chat(request.getMessages()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 流式回复 SSE
     * @param request 请求体
     * @return SseEmitter 流式Emitter
     */
    @PostMapping("/completions/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        LLMService service = factory.getLlmService(request.getModel());
        return service.stream(request.getMessages());
    }
}
