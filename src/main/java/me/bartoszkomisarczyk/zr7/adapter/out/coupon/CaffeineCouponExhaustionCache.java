package me.bartoszkomisarczyk.zr7.adapter.out.coupon;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCode;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponExhaustionCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CaffeineCouponExhaustionCache implements CouponExhaustionCache {

    private final Cache<String, Boolean> cache;

    public CaffeineCouponExhaustionCache(
            @Value("${coupon.exhaustion-cache.ttl:30m}") Duration ttl,
            @Value("${coupon.exhaustion-cache.max-size:10000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public boolean isExhausted(String code) {
        return Boolean.TRUE.equals(cache.getIfPresent(CouponCode.normalize(code)));
    }

    @Override
    public void markExhausted(String code) {
        cache.put(CouponCode.normalize(code), Boolean.TRUE);
    }
}