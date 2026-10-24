package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Eval 评分的每一个 Case
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalCase {
    private String id;                  // 全局唯一，如 "fact-001"
    private String kind;                // "llm" | "agent"
    private String category;            // factual / reasoning / tool-selection / format-adherence
    private JsonNode input;             // kind=llm -> {messages:[...]}; kind=agent -> {message, mode}
    private List<Criterion> criteria;   // 该 case 的单条评分标准
}
