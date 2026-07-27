package me.bartoszkomisarczyk.zr7.integration;

import me.bartoszkomisarczyk.zr7.adapter.geolocation.ipapi.IpApiGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;

/**
 * Sequential contract for coupon activation: the geolocation gate, the Caffeine cache that shields the
 * upstream ip-api 45 req/min limit, the four result paths and their database side effects, and the HTTP
 * status/body mapping for every {@link CouponUsageResult} variant. Geolocation is never real - the
 * {@link IpApiGeoLocationProvider} delegate is spied so each IP resolves to a fixed country.
 */
class CouponActivationIntegrationTest extends AbstractIntegrationTest {

    // RFC 5737 documentation IPs; the delegate is stubbed, so these never reach the network.
    // Happy paths resolve to PL and never assert delegate hit-counts, so they may share IP_PL.
    private static final String IP_PL = "203.0.113.10";
    private static final String IP_DE = "192.0.2.10";
    // Resolved only by the cache test, so the singleton Caffeine cache is always cold for it at start -
    // the hit-count assertion is independent of which tests ran first.
    private static final String IP_CACHE = "198.51.100.7";

    @Autowired
    private CouponService couponService;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoSpyBean
    private IpApiGeoLocationProvider geoLocationDelegate;

    @BeforeEach
    void stubGeoLocation() {
        doReturn(new GeoLocationResult("PL")).when(geoLocationDelegate).resolve(IP_PL);
        doReturn(new GeoLocationResult("PL")).when(geoLocationDelegate).resolve(IP_CACHE);
        doReturn(new GeoLocationResult("DE")).when(geoLocationDelegate).resolve(IP_DE);
    }

    // --- geolocation (no network) ---

    @Test
    void mismatchedCountryIsRejectedWithoutAnyWrite() throws Exception {
        long couponId = insertCoupon("GEO1", 5);

        CouponUsageResult result = couponService.activateCoupon("GEO1", 1L, IP_DE);

        assertInstanceOf(CouponUsageResult.CountryNotAllowed.class, result,
                "a request from a country other than the coupon's must be rejected");
        assertEquals(0, currentUsage(couponId), "the counter must not move on a rejected activation");
        assertEquals(0, usageCount(couponId), "no usage row may be written on a rejected activation");
    }

    @Test
    void geolocationProviderIsHitOncePerDistinctIp() throws Exception {
        insertCoupon("GEO2", 64); // max_usage comfortably above two activations

        // Two activations from the same IP (distinct users, so both succeed and both resolve).
        couponService.activateCoupon("GEO2", 1L, IP_CACHE);
        couponService.activateCoupon("GEO2", 2L, IP_CACHE);

        // The @Primary CachingGeoLocationProvider must serve the second resolve from cache - this is the
        // only guard against blowing the upstream 45 req/min free-tier limit.
        verify(geoLocationDelegate, times(1)).resolve(IP_CACHE);
    }

    // --- the four sequential result paths ---

    @Test
    void successIncrementsUsageAndRecordsTheRow() throws Exception {
        long couponId = insertCoupon("SEQ1", 5);

        CouponUsageResult result = couponService.activateCoupon("SEQ1", 1L, IP_PL);

        assertInstanceOf(CouponUsageResult.Success.class, result);
        assertEquals(1, currentUsage(couponId), "current_usage must advance by exactly one");
        assertEquals(1, usageCount(couponId), "exactly one usage row must exist");
    }

    @Test
    void unknownCodeReturnsNotFoundAndWritesNothing() throws Exception {
        long userWithoutCoupon = 5_000_001L;

        CouponUsageResult result = couponService.activateCoupon("SEQ2_NOPE", userWithoutCoupon, IP_PL);

        assertInstanceOf(CouponUsageResult.NotFound.class, result);
        assertEquals(0, usageCountByUser(userWithoutCoupon),
                "a request for an unknown code must not record any usage");
    }

    @Test
    void reuseBySameUserReturnsAlreadyUsedWithoutIncrement() throws Exception {
        long couponId = insertCoupon("SEQ3", 5);
        long user = 6_000_001L;

        couponService.activateCoupon("SEQ3", user, IP_PL);
        CouponUsageResult result = couponService.activateCoupon("SEQ3", user, IP_PL);

        assertInstanceOf(CouponUsageResult.AlreadyUsed.class, result);
        assertEquals(1, currentUsage(couponId), "current_usage must not advance on a duplicate use");
        assertEquals(1, usageCount(couponId), "the duplicate attempt must not add a usage row");
    }

    @Test
    void exhaustedCouponReturnsExhaustedWithoutIncrement() throws Exception {
        long couponId = insertCoupon("SEQ4", 1);
        long firstUser = 7_000_001L;
        long secondUser = 7_000_002L;

        couponService.activateCoupon("SEQ4", firstUser, IP_PL); // exhausts the single-use coupon
        CouponUsageResult result = couponService.activateCoupon("SEQ4", secondUser, IP_PL);

        assertInstanceOf(CouponUsageResult.Exhausted.class, result);
        assertEquals(1, currentUsage(couponId), "current_usage must stay pinned at max_usage");
        assertEquals(1, usageCount(couponId), "the exhausted attempt must not add a usage row");
    }

    // --- HTTP contract: each result variant maps to its status + body code ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("activateContractScenarios")
    void activateReturnsExpectedHttpStatusAndBodyCode(Scenario scenario) throws Exception {
        ActivateRequest request = scenario.seed().prepare(this);

        ResponseEntity<ActivateResponse> response = restTemplate.postForEntity(
                "/v1/api/coupons/activate", request, ActivateResponse.class);

        assertEquals(scenario.expectedStatus(), response.getStatusCode(),
                "unexpected HTTP status for scenario: " + scenario.name());
        assertEquals(scenario.expectedBody(), response.getBody().status(),
                "unexpected body status for scenario: " + scenario.name());
    }

    private record Scenario(String name, HttpStatus expectedStatus, String expectedBody, ScenarioSeed seed) {
    }

    @FunctionalInterface
    private interface ScenarioSeed {
        ActivateRequest prepare(CouponActivationIntegrationTest self) throws Exception;
    }

    private static Stream<Scenario> activateContractScenarios() {
        return Stream.of(
                new Scenario("success", HttpStatus.OK, "SUCCESS",
                        self -> {
                            self.insertCoupon("H1", 5);
                            return new ActivateRequest("H1", 1L, IP_PL);
                        }),
                new Scenario("not found", HttpStatus.NOT_FOUND, "NOT_FOUND",
                        self -> new ActivateRequest("H2_NOPE", 1L, IP_PL)),
                new Scenario("country not allowed", HttpStatus.FORBIDDEN, "COUNTRY_NOT_ALLOWED",
                        self -> {
                            self.insertCoupon("H3", 5);
                            return new ActivateRequest("H3", 1L, IP_DE);
                        }),
                new Scenario("already used", HttpStatus.CONFLICT, "ALREADY_USED",
                        self -> {
                            self.insertCoupon("H4", 5);
                            self.couponService.activateCoupon("H4", 1L, IP_PL);
                            return new ActivateRequest("H4", 1L, IP_PL);
                        }),
                new Scenario("exhausted", HttpStatus.CONFLICT, "EXHAUSTED",
                        self -> {
                            self.insertCoupon("H5", 1);
                            self.couponService.activateCoupon("H5", 1L, IP_PL);
                            return new ActivateRequest("H5", 2L, IP_PL);
                        })
        );
    }

    // --- HTTP contract: missing/blank request fields are rejected at the controller boundary ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidActivateRequests")
    void invalidActivationRequestIsRejectedWith400(String label, Map<String, ?> body) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/api/coupons/activate", body, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "a missing or blank field must be rejected as 400: " + label);
    }

    private static Stream<Arguments> invalidActivateRequests() {
        return Stream.of(
                arguments("empty code", Map.of("code", "", "userId", 1, "userIp", IP_PL)),
                arguments("missing code", Map.of("userId", 1, "userIp", IP_PL)),
                arguments("blank code", Map.of("code", "   ", "userId", 1, "userIp", IP_PL)),
                arguments("empty userIp", Map.of("code", "V1", "userId", 1, "userIp", ""))
        );
    }

    private record ActivateRequest(String code, long userId, String userIp) {
    }

    private record ActivateResponse(String status) {
    }
}