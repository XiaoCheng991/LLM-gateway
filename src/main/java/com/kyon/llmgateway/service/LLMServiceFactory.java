package com.kyon.llmgateway.service;

import com.kyon.llmgateway.adapter.BaseLLMAdapter;
import com.kyon.llmgateway.config.ModelConfig;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 根据 modelId 返回对应实现
 */
@Service
public class LLMServiceFactory {

    @Resource
    private ModelConfig modelConfig;

    private final Map<String, LLMService> providerMap;

    /**
     * 自动注入所有 LLMService 实现
     */
    public LLMServiceFactory(List<LLMService> services) {
        this.providerMap = services.stream()
                .filter(s -> s instanceof BaseLLMAdapter) // 只处理 BaseLLMAdapter 的子类
                .map(s -> (BaseLLMAdapter) s)
                .collect(Collectors.toMap(
                        BaseLLMAdapter::getProviderName,                          // KeyMap
                        Function.identity(),                                      // ValueMap
                        (existing, duplicate) -> existing   // 冲突时只保留一个
                ));
    }

    /**
     * 根据模型名，返回对应的 Adapter
     */
    public LLMService getLlmService(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Model name cannot be null");
        }

        String provider = modelConfig.getProviderByModel(modelName.toLowerCase());
        LLMService service = providerMap.get(provider);
        if (service == null) {
            throw new IllegalArgumentException("Unsupported provider: %s".formatted(provider));
        }

        // 把 model 传给 Adapter
        if (service instanceof BaseLLMAdapter adapter) {
            adapter.setCurrentModel(modelName);
        }
        return service;
    }
}
