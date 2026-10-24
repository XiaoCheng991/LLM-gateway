package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalReport {
    private String datasetName;
    private String datasetVersion;
    private String targetModel;
    private String judgeModel;      // 可为 null
    private String ranAt;           // ISO-8601

    private int totalCases;
    private int passed;
    private int failed;
    private int errors;
    private double passRate;        // passed / (passed + failed), errors 不进分母

    private Map<String, double[]> categoryStats;  // category -> [passed, total] (total 指参与判定数)
    private Map<String, Double> dimensionScores;  // llm_judge 维度均分，无则空 map

    private List<CaseResult> results;
}
