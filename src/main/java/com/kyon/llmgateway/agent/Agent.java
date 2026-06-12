package com.kyon.llmgateway.agent;

import java.util.function.Consumer;

public interface Agent {
    /**
     * 同步对话：走完 Tool Loop，一次返回最终结果
     */
    AgentResponse chat(AgentRequest request) throws Exception;

    /**
     * 流式对话：通过 Consumer 回调推送每个事件
     * 事件类型：content / tool_call / tool_result / done / error
     */
    void stream(AgentRequest request, Consumer<StreamEvent> emitter);
}
