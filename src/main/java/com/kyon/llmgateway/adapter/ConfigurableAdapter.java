package com.kyon.llmgateway.adapter;

public class ConfigurableAdapter extends BaseLLMAdapter {
    private final String provider;
    private final String baseUrl;
    private final String apiKey;

    public ConfigurableAdapter(String provider, String baseUrl, String apiKey) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String getProviderName() { return provider; }

    @Override
    protected String getBaseUrl() { return baseUrl; }

    @Override
    protected String getApiKey() { return apiKey; }

    @Override
    public String getEffectiveModel(String model) {
        // 去掉前缀
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名缺失");
        }

        int slashIdx = model.indexOf("/");
        if (slashIdx < 0) {
            // 没有前缀，原样返回
            return model;
        }
        String prefix = model.substring(0, slashIdx).toLowerCase();
        if (provider.equals(prefix)) {
            return model.substring(slashIdx + 1);
        }
        return model;
    }
}
