package me.bartoszkomisarczyk.zr7.domain.coupon;

import java.util.Optional;


public interface CouponRepository {

    // The activation-path projection: id + country only, and both are immutable once the coupon
    // exists, which is what makes this the one lookup safe to cache.
    Optional<CouponLookup> findLookupByCode(String code);

    // Unlike findLookupByCode, this must never be served from a cache: currentUsage changes on every
    // activation, so a caller reading a coupon back needs the live value, not a stale snapshot.
    Optional<Coupon> findByCode(String code);

    Coupon insert(String code, int maxUsage, String countryCode);

    int insertUsage(long couponId, long userId);

    int incrementUsage(long couponId);
}