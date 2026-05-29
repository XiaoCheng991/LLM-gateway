package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型内部类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ModelInfo {
    // 模型名称
    private String model;

    // 模型供应商
    private String provider;

    // 模型状态
    private String status;

}
