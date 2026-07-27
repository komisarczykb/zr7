package me.bartoszkomisarczyk.zr7.adapter.in.web.coupon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.ActivateCouponRequest;
import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.ActivateCouponResponse;
import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.CouponResponse;
import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.CreateCouponRequest;
import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCreationResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@RestController
@RequestMapping("/api/v1/coupons")
@Tag(name = "coupons", description = "Coupon creation, lookup and activation")
public class CouponController {

    private static final Logger log = LoggerFactory.getLogger(CouponController.class);

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    @Operation(summary = "Create a coupon")
    @ApiResponse(responseCode = "201", description = "Coupon created",
            content = @Content(schema = @Schema(implementation = CouponResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A coupon with this code already exists (case-insensitively)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> create(@Valid @RequestBody CreateCouponRequest request) {
        log.debug("Received request to create coupon {}", request.code());
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
    @Operation(summary = "Get a coupon by code",
            description = "currentUsage is always read live from the database, never from the lookup cache.")
    @ApiResponse(responseCode = "200", description = "Coupon found",
            content = @Content(schema = @Schema(implementation = CouponResponse.class)))
    @ApiResponse(responseCode = "404", description = "No coupon exists with this code",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> get(@Parameter(description = "Coupon code") @PathVariable String code) {
        return couponService.findCoupon(code)
                .<ResponseEntity<?>>map(coupon -> ResponseEntity.ok(CouponResponse.from(coupon)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/activate")
    @Operation(summary = "Activate (redeem) a coupon for a user",
            description = "Checks are applied in a fixed order regardless of cache warmth: coupon existence, "
                    + "then country match (resolved from userIp via the geolocation provider), then the "
                    + "exhaustion cache, then a single insert-usage + increment-usage transaction.")
    @ApiResponse(responseCode = "200", description = "Coupon activated successfully",
            content = @Content(schema = @Schema(implementation = ActivateCouponResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller's resolved country is not allowed to use this coupon",
            content = @Content(schema = @Schema(implementation = ActivateCouponResponse.class)))
    @ApiResponse(responseCode = "404", description = "No coupon exists with this code",
            content = @Content(schema = @Schema(implementation = ActivateCouponResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "This user already activated this coupon, or its usage limit is exhausted",
            content = @Content(schema = @Schema(implementation = ActivateCouponResponse.class)))
    @ApiResponse(responseCode = "503",
            description = "The geolocation provider could not be reached, timed out, or is rate-limiting us, so a "
                    + "country decision could not be made; fail-closed rather than let through or reported as "
                    + "COUNTRY_NOT_ALLOWED.",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ActivateCouponResponse.class)))
    public ResponseEntity<ActivateCouponResponse> activate(@Valid @RequestBody ActivateCouponRequest request) {
        log.debug("Received request to activate coupon {} for user {}", request.code(), request.userId());
        CouponUsageResult result = couponService.activateCoupon(
                request.code(), request.userId(), request.userIp());
        return switch (result) {
            case CouponUsageResult.Success r -> respond(HttpStatus.OK, "SUCCESS");
            case CouponUsageResult.NotFound r -> respond(HttpStatus.NOT_FOUND, "NOT_FOUND");
            case CouponUsageResult.CountryNotAllowed r -> respond(HttpStatus.FORBIDDEN, "COUNTRY_NOT_ALLOWED");
            case CouponUsageResult.AlreadyUsed r -> respond(HttpStatus.CONFLICT, "ALREADY_USED");
            case CouponUsageResult.Exhausted r -> respond(HttpStatus.CONFLICT, "EXHAUSTED");
            case CouponUsageResult.GeoLocationUnavailable r -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfterSeconds()))
                    .body(new ActivateCouponResponse("GEOLOCATION_UNAVAILABLE"));
        };
    }

    private static ResponseEntity<ActivateCouponResponse> respond(HttpStatus status, String token) {
        return ResponseEntity.status(status).body(new ActivateCouponResponse(token));
    }
}