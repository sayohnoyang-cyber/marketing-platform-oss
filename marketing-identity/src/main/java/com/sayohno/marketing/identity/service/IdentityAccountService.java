package com.sayohno.marketing.identity.service;

import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import org.springframework.stereotype.Service;

/**
 * 面向其他业务模块的账号只读服务：封装用户存在性与启用状态校验，避免外部模块直接耦合 iam_user Mapper。
 */
@Service
public class IdentityAccountService {
    private final IamUserMapper userMapper;

    public IdentityAccountService(IamUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 判断指定用户是否存在且处于启用状态。
     */
    public boolean isUserEnabled(long userId) {
        IamUser account = userMapper.selectById(userId);
        return account != null && account.getStatus() != null && account.getStatus() == IamStatus.ENABLED;
    }
}
