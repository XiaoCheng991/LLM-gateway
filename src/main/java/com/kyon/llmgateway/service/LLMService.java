package com.kyon.llmgateway.service;

import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 接口 - 定义 send() / stream()
 */
public interface LLMService {
    // Chat 方法 - 同步接口
    ChatResponse chat(List<Message> userMsgList) throws Exception;

    // SSE 流失方法
    SseEmitter stream(List<Message> userMsgList);

}
