package com.kyon.llmgateway.config;

import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.enums.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
    public ApiResult<Void> handleException(Exception e, HttpServletResponse response) {
        // SSE 响应已设 text/event-stream，回写 ApiResult 会撞 No converter 二次故障;
        // 客户端多半已断开，这里只记日志、不回写体
        if (isSse(response)) {
            if (isClientDisconnect(e)) {
                // 客户端主动断开：非故障，降为 DEBUG
                log.debug("SSE 响应期间异常，跳过 JSON 回写：{}", rootMessage(e));
            } else {
                log.warn("SSE 响应期间异常，跳过 JSON 回写：{}", rootMessage(e));
            }
            return null;
        }
        log.error("Unhandled exception: ", e);
        return ApiResult.error(ResultCode.INTERNAL_ERROR, e.getMessage());
    }

    /**
     * SSE 判断看响应实际 Content-Type：/api/agent/stream 靠 produces，/api/chat/completions 靠SseEmitter 处理器设置
     */
    private boolean isSse(HttpServletResponse response) {
        String ct = response.getContentType();
        return ct != null && ct.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /** 客户端断开判定：异常链含客户端侧专属异常，或消息含 disconnected client（容器侧断开通知）*/
    private boolean isClientDisconnect(Throwable e) {
        for (Throwable t = e; t != null; t = (t == t.getCause() ? null : t.getCause())) {
            String name = t.getClass().getName();
            if (name.endsWith("ClientAbortException") || name.endsWith("AsyncRequestNotUsableException")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains("disconnected client")) {
                return true;
            }
        }
        return false;
    }

    /** 拿到 root 信息 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
