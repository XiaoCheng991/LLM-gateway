package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用 Agnes 模型
 */
@Service
public class AgnesAdapter extends BaseLLMAdapter {

    @Value("${llm.agnes.base-url}")
    private String BASE_URL;

    @Value("${llm.agnes.api-key}")
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
        return "agnes";
    }
}
