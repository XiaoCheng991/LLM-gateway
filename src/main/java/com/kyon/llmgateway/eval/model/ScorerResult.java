package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScorerResult {
    private double score;         // 0.0 ~ 1.0
    private String reason;        // 评分理由, 进报告
    private boolean skipped;      // true = 该 criteria 未参与通过判定
    private Dimension dimension;  // llm_judge 时填，其余 null

    // 对外暴露的静态工厂方法：只暴露两个参数，第三个默认 false
    public static ScorerResult of(double score, String reason) {
        return new ScorerResult(score, reason, false, null);
    }

    public static ScorerResult of(double score, String reason, Dimension dimension) {
        return new ScorerResult(score, reason, false, dimension);
    }

    // judge 未配置 / judge 调用失败 -> 跳过，不判 fail
    public static ScorerResult skipped(String reason) {
        return new ScorerResult(0.0, reason, true, null);
    }
}
