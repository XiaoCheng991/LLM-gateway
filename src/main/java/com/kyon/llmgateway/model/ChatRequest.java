package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 *  {modelId, prompt, systemPrompt, temperature}
 */
@Data
@AllArgsConstructor
public class ChatRequest {

    // 模型名称
    public String model;

    // 消息
    public List<Message> messages;
}
