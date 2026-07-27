package me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateCouponRequest(
        @NotBlank @Size(max = 16) @Schema(example = "SUMMER2026") String code,
        @Positive @Schema(example = "100") int maxUsage,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$")
        @Schema(description = "ISO-3166-1 alpha-2 country code", example = "PL") String countryCode) {
}