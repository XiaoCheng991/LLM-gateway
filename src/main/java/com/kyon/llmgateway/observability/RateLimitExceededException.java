package com.kyon.llmgateway.observability;

import lombok.Getter;

/**
 * 自定义异常类，专门标识“限流被触发”这件事
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    // 被限流的 provider 名
    String provider;
    // 配置的限额
    int limit;

    /* 构造器 */
    public RateLimitExceededException(String provider, int limit) {
        super(String.format("Rate limit exceeded for provider [%s]. Limit: %d req/min at %d.", provider, limit, System.currentTimeMillis()));
        this.provider = provider;
        this.limit = limit;
    }

}
