package com.kyon.llmgateway.agent;

/**
 * Agent 模式枚举值
 */
public enum AgentMode {
    REACT,              // ReAct 模式，交替执行思考/行动，行动包括工具调用和最终回答
    PLAN_EXECUTE        // 计划执行模式，先生成完整的计划（思考/工具调用/最终回答），再统一执行计划
}


