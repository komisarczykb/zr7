package me.bartoszkomisarczyk.zr7.web.coupon;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCreationResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    public record ActivateCouponRequest(@NotBlank String code, long userId, @NotBlank String userIp) {
    }

    public record ActivateCouponResponse(String status) {
    }

    public record CreateCouponRequest(String code, int maxUsage, String countryCode) {
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateCouponRequest request) {
        CouponCreationResult result = couponService.createCoupon(
                request.code(), request.maxUsage(), request.countryCode());
        return switch (result) {
            case CouponCreationResult.Success r -> ResponseEntity.status(HttpStatus.CREATED).body(r.coupon());
            case CouponCreationResult.Conflict r -> ResponseEntity.status(HttpStatus.CONFLICT).body(r.cause());
        };
    }

    @PostMapping("/activate")
    public ResponseEntity<ActivateCouponResponse> activate(@Valid @RequestBody ActivateCouponRequest request) {
        CouponUsageResult result = couponService.activateCoupon(
                request.code(), request.userId(), request.userIp());
        return switch (result) {
            case Success r -> respond(HttpStatus.OK, "SUCCESS");
            case NotFound r -> respond(HttpStatus.NOT_FOUND, "NOT_FOUND");
            case CountryNotAllowed r -> respond(HttpStatus.FORBIDDEN, "COUNTRY_NOT_ALLOWED");
            case AlreadyUsed r -> respond(HttpStatus.CONFLICT, "ALREADY_USED");
            case Exhausted r -> respond(HttpStatus.CONFLICT, "EXHAUSTED");
        };
    }

    private static ResponseEntity<ActivateCouponResponse> respond(HttpStatus status, String token) {
        return ResponseEntity.status(status).body(new ActivateCouponResponse(token));
    }
}