package me.bartoszkomisarczyk.zr7.application.coupon;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Once a coupon is confirmed exhausted by the database, current_usage never decreases (no reset/limit-raise
 * endpoint exists), so the fact stays valid forever - caching it lets repeat activation attempts for an
 * exhausted code skip the database entirely instead of round-tripping on every retry.
 */
@Component
public class CouponExhaustionCache {

    private final Cache<String, Boolean> cache;

    public CouponExhaustionCache(
            @Value("${coupon.exhaustion-cache.ttl:30m}") Duration ttl,
            @Value("${coupon.exhaustion-cache.max-size:10000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    public boolean isExhausted(String code) {
        return Boolean.TRUE.equals(cache.getIfPresent(CouponCode.normalize(code)));
    }

    public void markExhausted(String code) {
        cache.put(CouponCode.normalize(code), Boolean.TRUE);
    }
}