package com.sayohno.marketing.common.result;

import org.slf4j.MDC;

public final class Results {
    private Results() {
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(ErrorCode.SUCCESS.code());
        result.setMessage(ErrorCode.SUCCESS.message());
        result.setData(data);
        result.setRequestId(MDC.get(RequestIds.MDC_KEY));
        return result;
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.message());
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        Result<T> result = new Result<>();
        result.setCode(errorCode.code());
        result.setMessage(message == null || message.isBlank() ? errorCode.message() : message);
        result.setRequestId(MDC.get(RequestIds.MDC_KEY));
        return result;
    }
}
