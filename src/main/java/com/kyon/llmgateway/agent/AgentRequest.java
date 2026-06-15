package com.kyon.llmgateway.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentRequest {
    /**
     * 会话 ID，为空则新建会话
     */
    private String sessionId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 模型名，如 deepseek-v4-pro
     */
    private String model;

    /**
     * Agent 模式，默认 REACT
     */
    private AgentMode mode = AgentMode.REACT;

    public AgentMode getMode() {
        return mode != null ? mode : AgentMode.REACT;
    }
}
