package me.bartoszkomisarczyk.zr7.domain.coupon;

import java.time.Instant;
import java.util.Locale;

public record Coupon(long id, String code, Instant creationDate, int maxUsage, int currentUsage, String countryCode) {
    public Coupon {
        countryCode = countryCode.toUpperCase(Locale.ROOT);
    }
}