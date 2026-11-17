package com.sayohno.marketing.support;

import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class MemoryStockConfiguration {
    @Bean
    @Primary
    public OfferStockGateway memoryCouponStockGateway() {
        return new MemoryOfferStockGateway();
    }
}
