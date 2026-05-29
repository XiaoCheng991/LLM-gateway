package com.kyon.llmgateway.config;

import com.kyon.llmgateway.model.ModelInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型配置类
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class ModelConfig {

    // 自动从 yml 绑定 llm.models[...]
    private List<ModelInfo> models;

    public String getProviderByModel(String modelName) {
        return models.stream()
                .filter(m -> m.getModel().equalsIgnoreCase(modelName))
                .findFirst()
                .map(ModelInfo::getProvider)
                .orElseThrow(() -> new IllegalArgumentException("未找到模型: " + modelName));    }
}
