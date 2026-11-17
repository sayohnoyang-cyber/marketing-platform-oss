package com.sayohno.marketing.shortlink.bloom;

import org.springframework.stereotype.Component;

/**
 * 布隆过滤器未就绪时继续查库，不能把“过滤器丢失”当成“数据不存在”。
 */
@Component
public class BloomReadPolicy {
    public boolean continueLookup(boolean bloomReady, boolean mightContain) {
        if (!bloomReady) {
            return true;
        }
        return mightContain;
    }
}
