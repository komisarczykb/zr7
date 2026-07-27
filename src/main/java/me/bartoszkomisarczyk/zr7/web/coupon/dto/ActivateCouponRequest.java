package me.bartoszkomisarczyk.zr7.web.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ActivateCouponRequest(@NotBlank @Size(max = 16) String code, @NotNull Long userId,
                                    @NotBlank String userIp) {
}