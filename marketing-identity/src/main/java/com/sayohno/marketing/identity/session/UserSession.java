package com.sayohno.marketing.identity.session;

import lombok.Data;

@Data
public class UserSession {
    private Long userId;
    private Long merchantId;
    private String storeCode;
    /** 登录签发时间（Epoch 秒），用于限制滑动续期的最长会话时长。 */
    private long loginAtEpochSeconds;
}
