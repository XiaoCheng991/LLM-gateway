package com.kyon.llmgateway.eval.scorer;

/**
 * 评测用的文本归一化：去掉「模型答的对但格式不通过」的噪声
 * 只为确定性 Scorer 服务，不做语义匹配
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * 归一化规则（按顺序执行）
     * 1. 全角 -> 半角（ＡＢＣ／１２３ → ABC/123）
     * 2. 转小写
     * 3. 中英文标点统一 （，。；："" '' （） → 对应半角或删除）
     * 4. 折叠连续空白为单空格
     * 5. trim
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String out = toHalfWidth(s).toLowerCase();
        out = out.replace('，', ',').replace('。', '.')
                .replace('；', ';').replace('：', ':')
                .replace('（', '(').replace('）', ')')
                .replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replace('、', ',');
        // 多个连续空白替换 \s+
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    /**
     * 全角 ASCII -> 半角
     */
    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c >= 0xFF01 && c <= 0xFF5E ? (char) (c - 0xFEE0) : c);
        }
        return sb.toString().replace('　', ' ');
    }

    /**
     * 提取缩略前
     */
    public static String preview(String s, int length) {
        return s.length() > length ? s.substring(0, length) + "..." : s;
    }
}
