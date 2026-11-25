package com.sayohno.marketing.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank(message = "请填写当前密码")
    private String oldPassword;

    @NotBlank(message = "请填写新密码")
    @Size(min = 8, max = 72, message = "新密码长度需为 8 到 72 位")
    private String newPassword;
}
