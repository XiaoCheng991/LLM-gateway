package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.model.*;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * POST /api/chat/completions - OpenAI 兼容纯转发端点
 * <p>
 * 职责边界
 * /api/chat/completions    -> 纯LLM转发，严格 OpenAI兼容（无Agent介入）
 * /api/chat/chat | stream  -> Agent 能力（tool loop、session、状态机）
 * </p>
 * 这里不执行 tool loop: 请求里带 tools 时只把工具定义透传给模型
 * 返回的 tool_calls 原样交换客户端处理
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Resource
    private LLMServiceFactory llmServiceFactory;


    /**
     * OpenAI 兼容对话端点。
     * <p>
     * stream=false → 返回 ApiResult<ChatResponse>
     * stream=true  → 返回 SseEmitter（text/event-stream）
     * </p>
     * 返回类型声明为 Object：Spring 按运行时类型分发——
     * SseEmitter 由 ResponseBodyEmitterReturnValueHandler 接手，
     * 普通对象由 RequestResponseBodyMethodProcessor 序列化。
     */
    @PostMapping("/completions")
    public Object chat(@RequestBody ChatRequest request) throws Exception {
        String model = request.getModel();

        LLMService service = llmServiceFactory.getLlmService(model);

        // 归一化：没有 tools 就不携带 tool_choice（否则上游 400）
        List<ToolDefinition> tools = request.getTools();
        JsonNode toolChoice = (tools == null || tools.isEmpty()) ? null : request.getToolChoice();

        // 开启 stream 则使用 stream 的方法
        if (Boolean.TRUE.equals(request.getStream())) {
            return service.stream(model, request.getMessages(), tools, toolChoice);
        }
        ChatResponse response = service.chat(model, request.getMessages(), tools, toolChoice);
        return ApiResult.success(response);
    }
}
