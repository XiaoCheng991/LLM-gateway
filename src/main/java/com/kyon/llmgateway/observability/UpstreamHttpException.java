package com.kyon.llmgateway.observability;

import lombok.Getter;

/**
 * 上游 HTTP 层错误（4xx/5xx）。
 * 与本地限流 RateLimitExceededException 刻意区分：
 *   本地限流 = 我们自己发太快，立刻重试只会更糟；
 *   上游 429/5xx = 上游暂时不给，退避后重试有意义。
 */
@Getter
public class UpstreamHttpException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public UpstreamHttpException(int statusCode, String body) {
        super("上游 HTTP %d: %s".formatted(statusCode, body));
        this.statusCode = statusCode;
        this.body = body;
    }
    // 429/503/504 属于可重试的瞬时故障；4xx 多半是请求本身有问题，重试无意义
    public boolean isRetryable() {
        return statusCode == 429 || statusCode == 503 || statusCode == 504;
    }
}
