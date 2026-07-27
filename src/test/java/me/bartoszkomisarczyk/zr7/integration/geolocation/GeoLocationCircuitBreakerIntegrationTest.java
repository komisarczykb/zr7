package me.bartoszkomisarczyk.zr7.integration.geolocation;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi.IpApiGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationUnresolvableException;
import me.bartoszkomisarczyk.zr7.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real ip-api adapter against a dead endpoint so the circuit breaker sees genuine transport
 * failures - a mocked delegate would bypass the AOP aspect that implements {@code @CircuitBreaker} and
 * prove nothing. Port 1 is closed, so every call fails immediately instead of waiting out a timeout.
 * <p>
 * The adapter is injected by its delegate qualifier, i.e. the caching decorator is deliberately out of
 * the path - this test is about the breaker alone. Thresholds are lowered purely to keep the test short;
 * production values live in {@code application.yaml}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "geolocation.ipapi.base-url=http://localhost:1/",
        "resilience4j.circuitbreaker.instances.ipapi.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.ipapi.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.ipapi.wait-duration-in-open-state=60s"
        })
class GeoLocationCircuitBreakerIntegrationTest extends AbstractIntegrationTest {

    private static final int CALLS_TO_OPEN = 4;

    @Autowired
    @Qualifier("delegateGeoLocationProvider")
    private GeoLocationProvider ipApiProvider;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("ipapi");
        circuitBreaker.reset();
    }

    @Test
    void adapterIsProxiedSoTheCircuitBreakerAspectApplies() {
        assertTrue(AopUtils.isAopProxy(ipApiProvider),
                "@CircuitBreaker is implemented as an aspect - without a proxy it silently does nothing");
        assertEquals(IpApiGeoLocationProvider.class, AopUtils.getTargetClass(ipApiProvider));
    }

    @Test
    void circuitOpensAfterRepeatedProviderFailuresAndThenFailsFast() {
        for (int i = 0; i < CALLS_TO_OPEN; i++) {
            assertThrows(GeoLocationException.class, () -> ipApiProvider.resolve("203.0.113.1"));
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Once open, the call is rejected without touching the network and surfaces as the same
        // GeoLocationException contract CouponService already maps to 503 GEOLOCATION_UNAVAILABLE.
        GeoLocationException rejected =
                assertThrows(GeoLocationException.class, () -> ipApiProvider.resolve("203.0.113.2"));
        assertEquals("Geolocation provider circuit is open", rejected.getMessage());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfNotPermittedCalls(),
                "exactly one call should have been rejected by the open circuit");
    }

    @Test
    void unresolvableIpsAreIgnoredByTheCircuitBreaker() {
        // Bad input (private/reserved/invalid IPs) must not open the circuit for everyone else.
        assertTrue(circuitBreaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
                .test(new GeoLocationUnresolvableException("IP is within private range")));
        assertFalse(circuitBreaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
                .test(new GeoLocationRateLimitedException("rate limited")));
        assertFalse(circuitBreaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
                .test(new GeoLocationException("upstream exploded")));
    }
}