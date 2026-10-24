package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条评分标准。type 决定走哪个 Scorer
 *   exact          -> ExactMatchScorer，value 中任一与输出归一化后相等即得分
 *   contains       -> ContainsScorer, 输出包含 value 中任一关键词即得分
 *   tool_calls     -> ToolCallScorer, Agent 实际调用过名为 tool 的工具即得分
 *   llm_judge      -> LlmJudgeScorer，按 dimension + prompt 由 judge 模型打 0～1 分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Criterion {
    // 类型，决定走哪个 Scorer
    private String type;

    // exact / contains 用
    private List<String> value;

    // tool_call 用
    private String tool;

    // llm_judge 用
    private Dimension dimension;

    // llm_judge 附加问题
    private String prompt;
}
