package com.kyon.llmgateway.service;

import com.kyon.llmgateway.adapter.NvidiaAdapter;
import com.kyon.llmgateway.adapter.OpenRouterAdapter;
import com.kyon.llmgateway.config.ModelConfig;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    private ModelConfig modelConfig;

    /**
     * 根据模型名，返回对应的 Adapter
     */
    public LLMService getLlmService(String modelName) {
        // 根据前缀来路由
        if (modelName == null || modelName.isBlank()) {
            throw new RuntimeException("Model name cannot be null");
        }
        // "openrouter/owl-alpha" | "nvidia/deepseek-ai/deepseek-v4-flash"
        String lowName = modelName.toLowerCase();
        String provider = modelConfig.getProviderByModel(lowName);

        return switch (provider) {
            case "openrouter" -> openRouterAdapter;
            case "nvidia" -> nvidiaAdapter;
            default -> throw new IllegalArgumentException("不支持的 Provider: %s".formatted(provider));
        };
    }
}
