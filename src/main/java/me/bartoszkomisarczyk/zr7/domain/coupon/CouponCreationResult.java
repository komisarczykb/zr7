package me.bartoszkomisarczyk.zr7.domain.coupon;

// All possible coupon creations are wrapped with one of those.
public sealed interface CouponCreationResult
        permits CouponCreationResult.Success,
        CouponCreationResult.Conflict {

    record Success(Coupon coupon) implements CouponCreationResult {
    }

    record Conflict(String cause) implements CouponCreationResult {
    }
}