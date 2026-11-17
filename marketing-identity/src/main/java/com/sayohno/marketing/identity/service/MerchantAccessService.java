package com.sayohno.marketing.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import org.springframework.stereotype.Service;

@Service
public class MerchantAccessService {
    private final IamShopMapper shopMapper;

    public MerchantAccessService(IamShopMapper shopMapper) {
        this.shopMapper = shopMapper;
    }

    public CurrentUser requireMerchant() {
        CurrentUser user = UserContext.require();
        if (!user.hasMerchantAccess()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public CurrentUser requireOwner() {
        CurrentUser user = requireMerchant();
        if (!user.owner()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要商家所有者权限");
        }
        return user;
    }

    public IamShop requireShop(String storeCode) {
        CurrentUser user = requireMerchant();
        IamShop shop = shopMapper.selectOne(Wrappers.<IamShop>lambdaQuery()
                .eq(IamShop::getStoreCode, storeCode));
        if (shop == null || shop.getStatus() == null || shop.getStatus() != IamStatus.ENABLED) {
            throw new BizException(ErrorCode.NOT_FOUND, "店铺不存在");
        }
        if (!user.getMerchantId().equals(shop.getMerchantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能操作其他商家的店铺");
        }
        return shop;
    }
}
