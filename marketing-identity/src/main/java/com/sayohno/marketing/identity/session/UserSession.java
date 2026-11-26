package com.sayohno.marketing.identity.session;

import lombok.Data;

@Data
public class UserSession {
    private Long userId;
    private Long merchantId;
    private String storeCode;
    /** 登录签发时间（Epoch 秒），用于限制滑动续期的最长会话时长。 */
    private long loginAtEpochSeconds;
    /** 用户级会话版本号，改密或全局踢下线时递增以立即吊销全端旧令牌。 */
    private long sessionVersion;
}
