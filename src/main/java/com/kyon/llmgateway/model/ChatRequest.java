package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 *  {modelId, prompt, systemPrompt, temperature}
 */
@Builder
@Data
@AllArgsConstructor
public class ChatRequest {

    // 模型名称
    private String model;

    // 消息
    private List<Message> messages;

    // 流式响应开关
    @Builder.Default
    private Boolean stream = false;
}
