package com.kyon.llmgateway.service;

import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.model.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 接口 - 定义 send() / stream()
 */
public interface LLMService {
    // Chat 方法 - 同步接口
    ChatResponse chat(List<Message> userMsgList, List<ToolDefinition> tools) throws Exception;

    /**
     * Chat 方法 - 同步接口，显式指定 tool_choice
     * 用 default 方法做向后兼容：老实现方/老调用方无需改动
     */
    default ChatResponse chat(List<Message> userMsgList, List<ToolDefinition> tools, String toolChoice) throws Exception {
        return chat(userMsgList, tools);
    }

    // SSE 流式方法 --- 透传给上游，不执行 tool loop
    SseEmitter stream(List<Message> userMsgList, List<ToolDefinition> tools);

    /**
     * 流式调用 LLM，同时累积完整响应，用于 Tool Loop
     * 默认抛异常，由 BaseLLMAdapter 覆盖实现
     */
    default ChatResponse chatStreaming(List<Message> messages, List<ToolDefinition> tools,
                                       java.util.function.Consumer<String> onContentDelta) throws Exception {
        throw new UnsupportedOperationException("流式 Tool Loop 未实现");
    }

}
