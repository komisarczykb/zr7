package me.bartoszkomisarczyk.zr7.web.coupon;

import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    public record ActivateCouponRequest(String code, long userId, String userIp) {
    }

    public record ActivateCouponResponse(String status) {
    }

    @PostMapping("/activate")
    public ResponseEntity<ActivateCouponResponse> activate(@RequestBody ActivateCouponRequest request) {
        CouponUsageResult result = couponService.activateCoupon(
                request.code(), request.userId(), request.userIp());
        // Exhaustive over the sealed CouponUsageResult - no fall-through default needed.
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