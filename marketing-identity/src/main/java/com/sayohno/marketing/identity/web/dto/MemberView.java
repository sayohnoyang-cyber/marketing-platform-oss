package com.sayohno.marketing.identity.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemberView {
    private String userId;
    private String username;
    private String role;
    private Integer status;
}
