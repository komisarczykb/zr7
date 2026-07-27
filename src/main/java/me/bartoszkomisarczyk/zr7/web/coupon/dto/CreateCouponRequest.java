package me.bartoszkomisarczyk.zr7.web.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateCouponRequest(@NotBlank @Size(max = 16) String code, @Positive int maxUsage,
                                  @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {
}