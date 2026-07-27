package me.bartoszkomisarczyk.zr7.domain.coupon;

public record Coupon(long id, String code, int maxUsage, int currentUsage, String countryCode) {
    public Coupon {
        countryCode = countryCode.toUpperCase();
    }
}