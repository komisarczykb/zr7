package me.bartoszkomisarczyk.zr7.domain.coupon;

import java.util.Optional;


public interface CouponRepository {

    Optional<CouponLookup> findByCode(String code);

    Coupon insert(String code, int maxUsage, String countryCode);

    int insertUsage(long couponId, long userId);

    int incrementUsage(long couponId);
}