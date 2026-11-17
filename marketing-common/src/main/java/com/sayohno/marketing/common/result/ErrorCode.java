package com.sayohno.marketing.common.result;

public enum ErrorCode {
    SUCCESS("0", "成功"),
    UNAUTHORIZED("A000001", "未登录或登录已过期"),
    FORBIDDEN("A000002", "没有权限访问该资源"),
    VALIDATION("A000003", "参数不合法"),
    NOT_FOUND("A000004", "数据不存在"),
    CONFLICT("A000005", "数据冲突"),
    BAD_CREDENTIAL("A000006", "用户名或密码错误"),
    DISABLED("A000007", "账号已停用"),
    RATE_LIMITED("A000008", "请求较多，请稍后重试"),
    SYSTEM("B000001", "系统执行出错");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
