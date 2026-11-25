package com.sayohno.marketing.common.campaign;

/** 校验短链关联的活动归属并保护活动主链，避免短链模块直接耦合活动领域模型。 */
public interface ActivityLinkGuard {
    void assertLinkable(long activityId);

    /** 校验短链是否为活动主短链；若是则禁止通过普通短链管理接口修改或回收。 */
    default void assertNotActivityMainLink(long linkId) {
    }
}
