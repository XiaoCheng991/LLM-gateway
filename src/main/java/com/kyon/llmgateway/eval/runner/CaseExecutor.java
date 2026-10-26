package com.kyon.llmgateway.eval.runner;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.EvalCase;

/**
 * 用例执行器：kind 决定实现
 * llm    -> LlmCaseExecutor    (裸调模型，不带工具、不带会话)
 * agent  -> AgentCaseExecutor  (走完整工具链 + 会话 + 状态机)
 * 约定：执行失败一律抛异常，由 EvalRunner 统一记 ERROR；执行器不吞异常。
 */
public interface CaseExecutor {

    /**
     * 与 EvalCase.kind 对应，作为路由键
     */
    String kind();

    /**
     * 执行一条用例，返回统一形态的产物
     *
     * @throws Exception 调用失败（网络、限流、参数非法等），由上层记 ERROR，不进通过率分母
     */
    CaseOutput execute(EvalCase evalCase, String targetModel) throws Exception;
}
