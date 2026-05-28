package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResult<T> {
    private Integer code;
    private String msg;
    private T data;
}
