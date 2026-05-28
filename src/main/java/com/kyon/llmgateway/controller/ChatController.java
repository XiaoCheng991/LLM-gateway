package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.model.ChatRequest;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/chat - 统一入口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private LLMServiceFactory factory;

    @PostMapping("completions")
    public ChatResponse chat(@RequestBody ChatRequest request) throws Exception {
        LLMService service = factory.getLlmService(request.getModel());

        // 从 request.getMessages() 中取出第一天 user 消息
        String userMsg = request.getMessages().getFirst().getContent();
        return service.chat(userMsg);
    }

}
