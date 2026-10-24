package com.kyon.llmgateway.eval.model;

/**
 * LLM-as-a-Judge 的评分维度
 * 命名对齐 RAGS / OpenAI Evals 约定，便于数据集迁移到 Python 生态
 */
public enum Dimension {
    ACCURACY,           // 事实准确
    RELEVANCE,          // 与问题相关
    FAITHFULNESS,       // 忠于给定上下文，不编造
    TOOL_CHOICE         // 工具选择与参数合理
}
