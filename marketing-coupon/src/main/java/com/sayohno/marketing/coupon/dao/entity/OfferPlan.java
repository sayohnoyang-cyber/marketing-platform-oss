package com.sayohno.marketing.coupon.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sayohno.marketing.common.mybatis.PersistentEntity;
import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offer_plan")
public class OfferPlan extends PersistentEntity {
    private Long merchantId;
    private String storeCode;
    private String title;
    private IssuerScope issuerScope;
    private ApplyScope applyScope;
    private String skuCode;
    private BenefitKind benefitKind;
    private Integer inventoryQty;
    private Integer issuedTotal;
    private LocalDateTime usableFrom;
    private LocalDateTime usableUntil;
    private String claimPolicy;
    private String benefitPolicy;
    private OfferPlanStatus status;
    @TableLogic
    private Integer delFlag;
    /** 各场仍可领取的剩余，不落库。 */
    @TableField(exist = false)
    private Integer openRemaining;
    /** 已发出的张数，含直接领取和活动领取，不落库。 */
    @TableField(exist = false)
    private Integer issuedCount;
}
