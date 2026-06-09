package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 *  模型请求
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

    // 工具定义列表 - 告诉 LLM 有哪些工具可用
    private List<ToolDefinition> tools;

    // 工具调用控制 - "auto" 自动选择 ｜ "none" 不调 ｜ "required" 强制调
    private String toolChoice;
}
