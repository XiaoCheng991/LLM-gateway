package com.kyon.llmgateway.service;

import com.kyon.llmgateway.adapter.NvidiaAdapter;
import com.kyon.llmgateway.adapter.OpenRouterAdapter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 根据 modelId 返回对应实现
 */
@Service
public class LLMServiceFactory {

    @Resource
    private OpenRouterAdapter openRouterAdapter;

    @Resource
    private NvidiaAdapter nvidiaAdapter;

    /**
     * 根据模型名，返回对应的 Adapter
     */
    public LLMService getLlmService(String modelName) {
        // 根据前缀来路由
        // "openrouter/owl-alpha" | "nvidia/deepseek-ai/deepseek-v4-flash"
        String prefix = modelName.split("/")[0].toLowerCase();

        return switch (prefix) {
            case "openrouter" -> openRouterAdapter;
            case "nvidia" -> nvidiaAdapter;
            default -> throw new IllegalArgumentException("暂未找到支持的 Provider: %s".formatted(prefix));
        };
    }
}
