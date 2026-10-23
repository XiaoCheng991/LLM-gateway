package com.kyon.llmgateway.agent.engine;

import java.util.regex.Pattern;

/**
 * <thinking>标签的单一定义处
 * <p>
 * 抽取器(ReActAgent的流式显示切分) 引用本类常量
 * 存储边界（两 Agent 的历史/最终回答）
 * 标签字符串全库只维护一份，避免双份定义漂移。
 */
public final class ThoughtTags {

    public static final String OPEN = "<thinking>";
    public static final String CLOSE = "</thinking>";

    private ThoughtTags() {
    }

    /**
     * 删除思考标签块后返回正文
     * <p>
     * 成对标签整块删除 ((?s) 让 . 匹配换行，思考块天然多行)
     * 游离闭合标签直接删除、未闭合的开标签是为“思考延续到结尾”，删至结尾
     * null/空串原样返回
     */
    public static String strip(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // 先清除两个标签中间的内容 ?s 允许 . 匹配换行符；.*? 非贪婪，让OPEN匹配到最近的CLOSE标签
        String cleaned = content.replaceAll(
                "(?s)" + Pattern.quote(OPEN) + ".*?" + Pattern.quote(CLOSE), "");

        // 再清除 CLOSE 标签
        cleaned = cleaned.replace(CLOSE, "");
        // 返回 OPEN 标签前面的内容
        int strayOpen = cleaned.indexOf(OPEN);
        if (strayOpen >= 0) {
            cleaned = cleaned.substring(0, strayOpen);
        }
        return cleaned;
    }
}
