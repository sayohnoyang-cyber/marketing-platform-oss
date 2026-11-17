package com.sayohno.marketing.common.result;

import lombok.Data;

import java.util.Map;

@Data
public class Result<T> {
    private String code;
    private String message;
    private T data;
    private String requestId;
    private Map<String, String> fields;

    public boolean success() {
        return ErrorCode.SUCCESS.code().equals(code);
    }
}
