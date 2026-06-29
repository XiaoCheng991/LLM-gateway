package com.kyon.llmgateway.observability;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器 - 按provider 维度限流
 * <p>
 * 原理：每个provider 有一个桶，容量 - requestPerMinute
 * 每次请求尝试从桶里取一滴水（token），取到放行，取不到抛异常
 * 桶会随时间自动补水，速率 = requestPerMinute / 60 每秒
 * <p>
 * 非消耗性预检（tryAcquireNonDestructive）:
 *   在 HTTP 请求前检查 - 不扣 token，只看够不够
 *   成功后（HTTP 200）才调用 recordSuccess 扣 token
 *   这样重试不会浪费配置
 */
@Component
public class TokenBucketRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    @Value("${agent.rate-limit.openrouter.requests-per-minute:100}")
    private int openrouterRpm;

    @Value("${agent.rate-limit.nvidia.requests-per-minute:50}")
    private int nvidiaRpm;

    @Value("${agent.rate-limit.agnes.requests-per-minute:30}")
    private int agnesRpm;

    // provider -> bucket 映射
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * 单个令牌桶
     */
    @Getter
    private static class TokenBucket {
        private final int maxTokens;                // 容量
        private final AtomicLong tokens;            // 当前可用 token 数
        private final AtomicLong lastRefillTimeMs;  // 上次补水时间

        private final Object lock = new Object();

        TokenBucket(int maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = new AtomicLong(maxTokens);
            this.lastRefillTimeMs = new AtomicLong(System.currentTimeMillis());
        }

        /**
         * 补水：根据经过的时间添加 token，不超过 maxTokens
         */
        private void refill() {
            long now = System.currentTimeMillis();

            // 经过的时间
            long elapsed = now - lastRefillTimeMs.get();
            if (elapsed <= 0) return;

            // 每秒补充 rate 个 token
            double rate = maxTokens / 60.0;
            long newTokens = (long) (elapsed * rate / 1000.0);
            if (newTokens > 0) {
                synchronized (lock) {
                    // 再次检查时间，防止并发重复补水
                    long currentTime = lastRefillTimeMs.get();
                    elapsed = now - currentTime;
                    if (elapsed <= 0) return;

                    long calculated = (long) (elapsed * rate / 1000.0);
                    tokens.addAndGet(Math.min(calculated, maxTokens - tokens.get()));
                    lastRefillTimeMs.set(System.currentTimeMillis());
                }
            }
        }

        /**
         * 非消耗性检查：看看能不能取，但不真取
         */
        boolean canTake() {
            synchronized (lock) {
                refill();
                return tokens.get() >= 1;
            }
        }

        /**
         * 消耗性取 token：成功取到返回 true，否则 false
         */
        boolean take() {
            synchronized (lock) {
                refill();
                long current = tokens.get();
                if (current >= 1) {
                    // 自动扣减
                    tokens.decrementAndGet();
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * 限流统计
     */
    public record RateLimitStats (
        String provider,
        int capacity,
        long remainingTokens,
        double refillRatePerSecond
    ) {}

    @PostConstruct
    public void init() {
        buckets.put("openrouter", new TokenBucket(openrouterRpm));
        buckets.put("nvidia", new TokenBucket(nvidiaRpm));
        buckets.put("agnes", new TokenBucket(agnesRpm));
        log.info("Rate limiter initialized: openrouter={}rpm, nvidia={}rpm, agnes={}rpm",
                openrouterRpm, nvidiaRpm, agnesRpm);
    }

    /**
     * 非消耗性预检 - 在发起 HTTP 请求前调用
     * 返回 true 表示 "够配额", 但不扣 token
     */
    public boolean tryAcquireNonDestructive(String provider) {
        TokenBucket bucket = getOrCreateBucket(provider);
        return bucket.canTake();
    }

    /**
     * 成功后扣 token - HTTP 200 后调用
     */
    public void recordSuccess(String provider) {
        TokenBucket bucket = getOrCreateBucket(provider);
        bucket.take();
    }

    /**
     * 旧版消耗性方法 (已废弃，保留兼容)
     */
    @Deprecated
    public boolean tryAcquire(String provider) {
        TokenBucket bucket = getOrCreateBucket(provider);
        return bucket.take();
    }

    private TokenBucket getOrCreateBucket(String provider) {
        return buckets.computeIfAbsent(provider, p -> {
            log.warn("Unknown provider '{}', using default 60 rpm", p);
            return new TokenBucket(60);
        });
    }

    /**
     * 获取 provider 的容量上限 (默认60)
     */
    public int getMaxTokensForProvider(String provider) {
        TokenBucket bucket = buckets.get(provider);
        return bucket != null ? bucket.getMaxTokens() : 60;
    }

    /**
     * 动态配置
     */
    public void configure(String provider, int requestPerMinute) {
        TokenBucket bucket = buckets.get(provider);
        if (bucket != null) {
            // 需要新建 bucket 才能改容量，这里简单记录日志
            log.info("Reconfigure not yet implemented for '{}', current={}", provider, bucket.getMaxTokens());
        }
    }

    /**
     * 获取限流统计
     */
    public RateLimitStats getStats(String provider) {
        TokenBucket bucket = getOrCreateBucket(provider);
        double rate = bucket.getMaxTokens() / 60.0;
        return new RateLimitStats(provider, bucket.getMaxTokens(), bucket.tokens.get(), rate);
    }
}
