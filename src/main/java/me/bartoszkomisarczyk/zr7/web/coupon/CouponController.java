package me.bartoszkomisarczyk.zr7.web.coupon;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCreationResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/v1/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    public record ActivateCouponRequest(@NotBlank @Size(max = 16) String code, @NotNull Long userId,
                                         @NotBlank String userIp) {
    }

    public record ActivateCouponResponse(String status) {
    }

    public record CreateCouponRequest(@NotBlank @Size(max = 16) String code, @Positive int maxUsage,
                                       @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {
    }

    // The wire representation of a coupon — deliberately excludes the internal database id
    // (README decision #2: the primary key never leaves the database).
    public record CouponResponse(String code, Instant creationDate, int maxUsage, int currentUsage,
                                  String countryCode) {
        static CouponResponse from(Coupon coupon) {
            return new CouponResponse(coupon.code(), coupon.creationDate(), coupon.maxUsage(),
                    coupon.currentUsage(), coupon.countryCode());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateCouponRequest request) {
        CouponCreationResult result = couponService.createCoupon(
                request.code(), request.maxUsage(), request.countryCode());
        return switch (result) {
            case CouponCreationResult.Success r ->
                    ResponseEntity.status(HttpStatus.CREATED).body(CouponResponse.from(r.coupon()));
            case CouponCreationResult.Conflict r ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, r.cause()));
        };
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> get(@PathVariable String code) {
        return couponService.findCoupon(code)
                .<ResponseEntity<?>>map(coupon -> ResponseEntity.ok(CouponResponse.from(coupon)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)));
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
            case GeoLocationUnavailable r -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfterSeconds()))
                    .body(new ActivateCouponResponse("GEOLOCATION_UNAVAILABLE"));
        };
    }

    private static ResponseEntity<ActivateCouponResponse> respond(HttpStatus status, String token) {
        return ResponseEntity.status(status).body(new ActivateCouponResponse(token));
    }
}