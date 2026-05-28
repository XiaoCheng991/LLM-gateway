package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用 OpenRouter
 */
@Service
public class OpenRouterAdapter extends BaseLLMAdapter {

    private static final String MODEL = "openrouter/owl-alpha";

    @Value("${llm.openrouter.base-url}")
    private String BASE_URL;

    @Value("${llm.openrouter.api-key}")
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
    protected String getModelName() {
        return MODEL;
    }
}

