package me.bartoszkomisarczyk.zr7.domain.coupon;

import java.util.Locale;

// Single place to normalize a coupon code for case-insensitive comparison, matching
// PostgreSQL's UPPER() regardless of the JVM's default locale (e.g. tr-TR).
public final class CouponCode {

    private CouponCode() {
    }

    public static String normalize(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}