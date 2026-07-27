package me.bartoszkomisarczyk.zr7.domain.coupon;

//todo: caching so id - country can be resolved easily;
public record CouponLookup(long id, String countryCode) {
}