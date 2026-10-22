package com.kyon.llmgateway.agent.state;

import java.util.Map;
import java.util.Set;

public enum AgentState {
    /*
     *                        Agent 状态机
     *                           ┌────────────────┐
     *                           ▼                │
     *       ┌────────┐     ┌──────────┐     ┌──────────┐
     *       │  IDLE  │────▶│ THINKING │────▶│  ACTING  │
     *       └────────┘     └──────────┘     └──────────┘
     *            │              │                │
     *            │              ▼                ▼
     *            │         ┌────────┐      ┌────────┐
     *            └────────▶│  DONE  │      │ ERROR  │
     *                      └────────┘      └────────┘
     *                           └───────┬───────┘
     *                                   ▼
     *                               ┌────────┐
     *                               │  IDLE  │  (重置)
     *                               └────────┘
     */
    IDLE,
    THINKING,
    ACTING,
    PLANNING,
    DONE,
    ERROR;

    // Map.of() 方法最多10个键值对
    private static final Map<AgentState, Set<AgentState>> TRANSITIONS = Map.of(
            IDLE, Set.of(THINKING, PLANNING, ERROR),
            THINKING, Set.of(ACTING, DONE, ERROR),
            ACTING, Set.of(THINKING, DONE, ERROR),
            PLANNING, Set.of(THINKING, DONE, ERROR),
            // 终态重入：DONE/ERROR 是"请求之间"的驿站，不是 session 的终点——
            // 新请求（含失败重试）从终态直接进新一轮
            DONE, Set.of(IDLE, THINKING, PLANNING),
            ERROR, Set.of(IDLE, THINKING, PLANNING)
    );

    /**
     * 状态机转换为下一状态
     */
    public void transitionTo(AgentState next) {
        Set<AgentState> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(next)) {
            throw new IllegalStateException("Invalid state transition: " + this + " -> " + next + ". Allowed: " + allowed);
        }
    }
}
