package me.bartoszkomisarczyk.zr7.domain.coupon;

// All possible coupon retrievals are wrapped with one of those.
public sealed interface CouponUsageResult
        permits CouponUsageResult.Success,
        CouponUsageResult.AlreadyUsed,
        CouponUsageResult.Exhausted,
        CouponUsageResult.NotFound,
        CouponUsageResult.CountryNotAllowed,
        CouponUsageResult.GeoLocationUnavailable {

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

    // The geolocation provider could not be reached or is rate-limiting us — a policy
    // decision could not be made, so this must not be reported as CountryNotAllowed.
    record GeoLocationUnavailable(int retryAfterSeconds) implements CouponUsageResult {
    }
}