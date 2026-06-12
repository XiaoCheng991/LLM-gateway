package com.kyon.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    // 消息角色
    private String role;

    // 消息内容
    private String content;

    // assistant 消息带带 tool_calls
    private JsonNode toolCalls;

    // tool 消息需要的 id
    private String toolCallId;

    // tool 消息需要的工具名
    private String name;
}
