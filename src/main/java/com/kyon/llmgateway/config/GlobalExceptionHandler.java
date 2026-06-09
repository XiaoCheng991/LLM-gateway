package com.kyon.llmgateway.config;

import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.enums.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. 处理 IllegalArgumentException --- 客户端传参错误
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResult.error(ResultCode.BAD_REQUEST, e.getMessage());
    }

    // 2. 兜底 --- 所有未捕获的异常
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception e) {
        log.error("Unhandled exception: ", e);
        return ApiResult.error(ResultCode.INTERNAL_ERROR, e.getMessage());
    }
}
