package com.sayohno.marketing.common.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class UserContextTest {

    @Test
    void clearRemovesUserOnThisThread() {
        CurrentUser first = CurrentUser.builder().userId(1L).username("a").roles(Set.of(UserRole.USER)).build();
        CurrentUser second = CurrentUser.builder().userId(2L).username("b").roles(Set.of(UserRole.USER)).build();
        UserContext.set(first);
        assertSame(first, UserContext.get());
        UserContext.clear();
        assertNull(UserContext.get());
        UserContext.set(second);
        assertSame(second, UserContext.get());
        UserContext.clear();
        assertNull(UserContext.get());
    }
}
