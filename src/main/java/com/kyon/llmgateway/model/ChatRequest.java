package com.kyon.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型请求
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
     * 工具调用控制，OpenAI 规范的联合类型（string | object）：
     * "auto" / "none" / "required"                   —— 字符串形态
     * {"type":"function","function":{"name":"..."}}   —— 对象形态（强制指定函数）
     * 用 JsonNode 承载：反序列化两种形态都收，序列化原样转发（网关忠实搬运，校验交给上游）。
     */
    @JsonProperty("tool_choice")
    private JsonNode toolChoice;
}
