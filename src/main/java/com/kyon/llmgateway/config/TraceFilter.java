package com.kyon.llmgateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Logger logger = LoggerFactory.getLogger(TraceFilter.class);

        // MDC 全链路追踪，依据TraceId
        UUID traceId = UUID.randomUUID();
        MDC.put("traceId", traceId.toString());

        try {
            logger.info("TraceFilter: {} {} ]", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            // 确保清理 MDC，防止内存泄漏
            MDC.clear();
        }
    }
}
