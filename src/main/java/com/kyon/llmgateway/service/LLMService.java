package com.kyon.llmgateway.service;

import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;

import java.util.List;

/**
 * 接口 - 定义 send() / stream()
 */
public interface LLMService {
    ChatResponse chat(List<Message> userMsgList) throws Exception;
}
