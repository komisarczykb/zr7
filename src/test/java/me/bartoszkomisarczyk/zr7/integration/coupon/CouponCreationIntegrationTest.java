package me.bartoszkomisarczyk.zr7.integration.coupon;

import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.CouponResponse;
import me.bartoszkomisarczyk.zr7.adapter.in.web.coupon.dto.CreateCouponRequest;
import me.bartoszkomisarczyk.zr7.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * HTTP contract for coupon creation and read-back: the happy path, the case-insensitive
 * uniqueness conflict, invalid-field rejection, and that all five brief-required fields
 * (code, creation date, max usage, current usage, country) round-trip without leaking the
 * internal database id.
 */
@SuppressWarnings("unchecked")
class CouponCreationIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_PATH = "/api/v1/coupons";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createReturns201WithoutLeakingTheDatabaseId() {
        CreateCouponRequest request = new CreateCouponRequest("NEWCOUPON1", 5, "PL");

        ResponseEntity<Map> response = restTemplate.postForEntity(BASE_PATH, request, Map.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("NEWCOUPON1", body.get("code"));
        assertEquals(5, body.get("maxUsage"));
        assertEquals(0, body.get("currentUsage"));
        assertEquals("PL", body.get("countryCode"));
        assertNotNull(body.get("creationDate"), "creationDate must be surfaced through the API");
        assertFalse(body.containsKey("id"), "the internal database id must never be exposed on the wire");
    }

    @Test
    void createIsCaseInsensitiveOnUniqueness() {
        restTemplate.postForEntity(BASE_PATH, new CreateCouponRequest("WIOSNA", 5, "PL"), CouponResponse.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                BASE_PATH, new CreateCouponRequest("wiosna", 5, "PL"), String.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                "wiosna must conflict with an existing WIOSNA regardless of case");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateRequests")
    void invalidCreateRequestIsRejectedWith400(String label, Map<String, ?> body) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                BASE_PATH, body, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "an invalid field must be rejected as 400, not leak through as a 500: " + label);
    }

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                arguments("blank code", Map.of("code", "", "maxUsage", 5, "countryCode", "PL")),
                arguments("code over 16 chars", Map.of("code", "A".repeat(17), "maxUsage", 5, "countryCode", "PL")),
                arguments("zero maxUsage", Map.of("code", "BADMAX0", "maxUsage", 0, "countryCode", "PL")),
                arguments("negative maxUsage", Map.of("code", "BADMAXN", "maxUsage", -1, "countryCode", "PL")),
                arguments("three-letter country", Map.of("code", "BADCTRY1", "maxUsage", 5, "countryCode", "PLX")),
                arguments("blank country", Map.of("code", "BADCTRY2", "maxUsage", 5, "countryCode", ""))
        );
    }

    @Test
    void getReturnsTheCreatedCouponWithAllFields() {
        restTemplate.postForEntity(BASE_PATH, new CreateCouponRequest("READBACK1", 3, "DE"), CouponResponse.class);

        ResponseEntity<CouponResponse> response = restTemplate.getForEntity(
                BASE_PATH + "/{code}", CouponResponse.class, "READBACK1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CouponResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("READBACK1", body.code());
        assertEquals(3, body.maxUsage());
        assertEquals(0, body.currentUsage());
        assertEquals("DE", body.countryCode());
        assertNotNull(body.creationDate());
    }

    @Test
    void getIsCaseInsensitive() {
        restTemplate.postForEntity(BASE_PATH, new CreateCouponRequest("CASECHK1", 3, "PL"), CouponResponse.class);

        ResponseEntity<CouponResponse> response = restTemplate.getForEntity(
                BASE_PATH + "/{code}", CouponResponse.class, "casechk1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getUnknownCodeReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                BASE_PATH + "/{code}", String.class, "NOPE_UNKNOWN");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}