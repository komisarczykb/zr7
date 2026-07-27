package me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ActivateCouponRequest(
        @NotBlank @Size(max = 16) String code,
        @NotNull Long userId,
        @NotBlank @Schema(example = "203.0.113.42") String userIp) {
}