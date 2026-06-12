package com.kyon.llmgateway.agent;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StreamEvent {
    public enum Type {
        CONTENT,    // 内容增量
        TOOL_CAL,   // 工具调用
        TOOL_RESULT,// 工具结果
        DONE,       // 完成
        ERROR       // 错误
    }

    private Type type;
    private String data;        // JSON 数据，下游按 type 反序列化
    private String rawContent;  // 仅 CONTENT 用，直接追加文本

    // --- 工厂方法 ---
    public static StreamEvent content(String delta) {
        StreamEvent event = new StreamEvent();
        event.setType(Type.CONTENT);
        event.setRawContent(delta);
        return event;
    }

    public static StreamEvent toolCall(String toolCallJson) {
        StreamEvent event = new StreamEvent();
        event.setType(Type.TOOL_CAL);
        event.setData(toolCallJson);
        return event;
    }

    public static StreamEvent toolResult(String resultJson) {
        StreamEvent event = new StreamEvent();
        event.setType(Type.TOOL_RESULT);
        event.setData(resultJson);
        return event;
    }

    public static StreamEvent done() {
        StreamEvent event = new StreamEvent();
        event.setType(Type.DONE);
        return event;
    }

    public static StreamEvent error(String error) {
        StreamEvent event = new StreamEvent();
        event.setType(Type.ERROR);
        event.setData(error);
        return event;
    }

}
