package com.sayohno.marketing.support;

import com.sayohno.marketing.identity.session.SessionStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestIdentityConfiguration {

    @Bean
    public SessionStore sessionStore() {
        return new MemorySessionStore();
    }
}
