package com.kyon.llmgateway.service;

import com.kyon.llmgateway.model.ChatResponse;

/**
 * 接口 - 定义 send() / stream()
 */
public interface LLMService {
    ChatResponse chat(String userMessage) throws Exception;
}
