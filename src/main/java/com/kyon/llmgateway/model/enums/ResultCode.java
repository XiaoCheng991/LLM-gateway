package com.kyon.llmgateway.model.enums;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(200, "success"),
    CREATED(201, "created"),
    FOUND(302, "found"),
    TEMPORARY_REDIRECT(307, "temporary redirect"),
    PERMANENT_REDIRECT(308, "permanent redirect"),
    BAD_REQUEST(400, "bad request"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal server error");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
