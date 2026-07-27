package me.bartoszkomisarczyk.zr7.domain.coupon;

/**
 * Once a coupon is confirmed exhausted by the database, current_usage never decreases (no reset/limit-raise
 * endpoint exists), so the fact stays valid forever - caching it lets repeat activation attempts for an
 * exhausted code skip the database entirely instead of round-tripping on every retry.
 */
public interface CouponExhaustionCache {

    boolean isExhausted(String code);

    void markExhausted(String code);
}