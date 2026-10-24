package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 评估案例结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseResult {
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String ERROR = "ERROR";

    private String caseId;
    private String category;
    private String kind;
    private String status;                          // PASS / FAIL / ERROR
    private String output;                          // 截断后的实际输出
    private String errorMessage;                    // status = ERROR 时有值
    private long latencyMs;
    private Map<String, ScorerResult> scores;       // key = criteria 的 type 或 "type#序号"
    private List<String> skippedReasons;
}
