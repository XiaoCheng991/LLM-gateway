package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次用例执行的原始产出，交给 Scorer 评分
 * content 是最终文本：toolNames 是 Agent 实际调用过的工具名（按调用顺序）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseOutput {
    private String content;
    private List<String> toolNames;
    private long latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
}
