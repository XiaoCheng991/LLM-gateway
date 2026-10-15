package com.kyon.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    /**
     * 工具调用控制 - "auto" 自动选择 ｜ "none" 不调 ｜ "required" 强制调
     * OpenAI 规范字段名为 tool_choice（蛇形），不加 @JsonProperty 会导致
     * 序列化成 toolChoice，被上游静默忽略
     */
    @JsonProperty("tool_choice")
    private String toolChoice;
}
