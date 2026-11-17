package com.sayohno.marketing.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "需为 4 到 32 位字母、数字或下划线")
    private String username;

    @NotBlank
    @Size(min = 8, max = 72)
    private String password;

    @NotNull
    private RegisterType registerType = RegisterType.WORKSPACE;

    @Size(max = 128)
    private String merchantName;

    @Size(max = 128)
    private String shopName;
}
