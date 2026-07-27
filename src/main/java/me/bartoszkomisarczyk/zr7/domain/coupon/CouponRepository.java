package me.bartoszkomisarczyk.zr7.domain.coupon;

import java.util.Optional;


public interface CouponRepository {

    Optional<CouponLookup> findByCode(String code);

    // Unlike findByCode, this must never be served from a cache: currentUsage changes on every
    // activation, so a caller reading a coupon back needs the live value, not a stale snapshot.
    Optional<Coupon> findFullByCode(String code);

    Coupon insert(String code, int maxUsage, String countryCode);

    int insertUsage(long couponId, long userId);

    int incrementUsage(long couponId);
}