package me.bartoszkomisarczyk.zr7.domain.coupon;

// All possible coupon retrievals are wrapped with one of those.
public sealed interface CouponUsageResult
        permits CouponUsageResult.Success,
        CouponUsageResult.AlreadyUsed,
        CouponUsageResult.Exhausted,
        CouponUsageResult.NotFound,
        CouponUsageResult.CountryNotAllowed {

    record Success() implements CouponUsageResult {
    }

    record AlreadyUsed() implements CouponUsageResult {
    }

    record Exhausted() implements CouponUsageResult {
    }

    record NotFound() implements CouponUsageResult {
    }

    record CountryNotAllowed() implements CouponUsageResult {
    }
}