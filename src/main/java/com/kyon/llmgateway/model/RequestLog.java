package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RequestLog {
    private Long id;
    private String requestId;
    private String provider;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long latencyMs;
    private String status;
    private String errorMessage;
    private Long timestamp;
}
