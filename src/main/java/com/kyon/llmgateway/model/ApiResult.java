package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResult<T> {
    private Integer code;
    private String msg;
    private T data;

    // 静态工厂方法
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> ApiResult<T> error(ResultCode resultCode) {
       return new ApiResult<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    public static <T> ApiResult<T> error(ResultCode resultCode, String customMsg) {
        return new ApiResult<>(resultCode.getCode(), customMsg, null);
    }
}
