package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用 Nvidia 模型
 */
@Service
public class NvidiaAdapter extends BaseLLMAdapter {

    @Value("${llm.nvidia.base-url}")
    private String BASE_URL;

    @Value("${llm.nvidia.api-key}")
    private String API_KEY;

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected String getApiKey() {
        return API_KEY;
    }

    @Override
    public String getProviderName() {
        return "nvidia";
    }

    @Override
    public String getEffectiveModel() {
        // 去掉前缀
        String model = super.getCurrentModel();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名缺失");
        }

        int slashIdx = model.indexOf("/");
        if (slashIdx < 0) {
            // 没有前缀，原样返回
            return model;
        }
        String prefix = model.substring(0, slashIdx).toLowerCase();
        if ("nvidia".equals(prefix)) {
            return model.substring(slashIdx + 1);
        }
        return model;
    }
}
