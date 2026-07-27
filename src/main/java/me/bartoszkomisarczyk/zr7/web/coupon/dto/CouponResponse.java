package me.bartoszkomisarczyk.zr7.web.coupon.dto;

import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;

import java.time.Instant;

// The wire representation of a coupon — deliberately excludes the internal database id
// (README decision #2: the primary key never leaves the database).
public record CouponResponse(String code, Instant creationDate, int maxUsage, int currentUsage,
                             String countryCode) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(coupon.code(), coupon.creationDate(), coupon.maxUsage(),
                coupon.currentUsage(), coupon.countryCode());
    }
}