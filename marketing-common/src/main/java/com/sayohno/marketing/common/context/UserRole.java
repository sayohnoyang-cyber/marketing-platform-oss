package com.sayohno.marketing.common.context;

public final class UserRole {
    public static final String USER = "USER";
    public static final String OWNER = "OWNER";
    public static final String OPERATOR = "OPERATOR";

    private UserRole() {
    }

    public static boolean merchantRole(String role) {
        return OWNER.equals(role) || OPERATOR.equals(role);
    }
}
