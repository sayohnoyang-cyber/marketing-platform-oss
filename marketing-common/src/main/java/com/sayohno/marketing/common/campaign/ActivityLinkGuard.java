package com.sayohno.marketing.common.campaign;

/** Validates activity ownership without coupling the short-link module to campaigns. */
public interface ActivityLinkGuard {
    void assertLinkable(long activityId);
}
