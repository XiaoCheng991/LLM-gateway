package com.kyon.llmgateway.agent.engine;

import com.kyon.llmgateway.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenCounter {

    /**
     * 估算文本的 token 数
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;

        // 统计中英文个数
        for (char c: text.toCharArray()) {
            // 每个中文字符算 1.5 个 token，英文字符算 0.25 个 token
            if (c > 0x4e00 && c < 0x9fff) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        // 估算 token 数 (+10 是安全余量，覆盖特殊符号和 分词器 tokenizer 的边界开销)
        return (int) (chineseChars * 1.5 + otherChars / 4.0) + 10;
    }

    /**
     * 估算消息列表总 token 数
     */
    public static int estimateMessages(List<Message> messages) {
        // +5 是消息格式的开销(role、content 等字段的 token成本)
        return messages.stream()
                .mapToInt(m -> estimateTokens(m.getContent()) + 5)
                .sum();
    }
}
