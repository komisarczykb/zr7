package me.bartoszkomisarczyk.zr7.integration.coupon;

import me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi.IpApiGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.application.coupon.CouponService;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import me.bartoszkomisarczyk.zr7.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

/**
 * Concurrency contract for coupon activation. Under load the database-level guards - the conditional
 * increment {@code UPDATE ... WHERE current_usage < max_usage}, the per-{@code (coupon, user)} unique
 * constraint, and the rollback that discards a usage row when the coupon is already exhausted - must
 * hold regardless of how requests interleave. Each test pins one invariant that is deterministic by
 * construction, so a failure is never a timing flake: it means a guard regressed.
 * <p>
 * Activation is driven straight through {@link CouponService} rather than over HTTP: the race lives
 * in the service and the database, and the service hands back the typed {@link CouponUsageResult} the
 * assertions partition by.
 */
class CouponConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // RFC 5737 documentation IP. The Caffeine geolocation cache is a singleton shared across the suite,
    // so this must stay distinct from the IPs GeoLocationCachingIntegrationTest asserts hit-counts on
    // (8.8.8.8 / 1.1.1.1) - otherwise resolving it here would poison the cache and break that test.
    private static final String TEST_IP = "203.0.113.10";

    @Autowired
    private CouponService couponService;

    /*The @Primary CachingGeoLocationProvider sits in front of this delegate; stubbing the delegate
    controls every resolution without touching the network.*/
    @MockitoSpyBean
    private IpApiGeoLocationProvider geoLocationDelegate;

    @BeforeEach
    void stubGeoLocation() {
        doReturn(new GeoLocationResult("PL")).when(geoLocationDelegate).resolve(TEST_IP);
    }

    @Test
    void concurrentActivationByDistinctUsersRespectsMaxUsage() throws Exception {
        int threads = 32;
        int maxUsage = 8;
        long couponId = insertCoupon("CONC1", maxUsage);

        List<Long> userIds = LongStream.range(0, threads).map(i -> 1_000_001L + i).boxed().toList();
        List<CouponUsageResult> results = activateConcurrently("CONC1", userIds);

        long successes = count(results, CouponUsageResult.Success.class);
        long exhausted = count(results, CouponUsageResult.Exhausted.class);

        assertEquals(maxUsage, successes, "exactly max_usage activations may succeed");
        assertEquals(threads - maxUsage, exhausted, "the rest must be rejected as exhausted");
        assertEquals(threads, successes + exhausted, "no activation may take any other path");

        // Invariants: the counter never overshoots the cap, and every success leaves exactly one row.
        assertEquals(maxUsage, currentUsage(couponId), "current_usage must equal max_usage");
        assertEquals(maxUsage, usageCount(couponId), "every success must have exactly one usage row");
    }

    @Test
    void concurrentActivationBySameUserSucceedsOnce() throws Exception {
        int threads = 32;
        long couponId = insertCoupon("CONC2", 64); // max_usage comfortably above the thread count
        long userId = 2_000_001L;

        List<Long> userIds = Collections.nCopies(threads, userId);
        List<CouponUsageResult> results = activateConcurrently("CONC2", userIds);

        long successes = count(results, CouponUsageResult.Success.class);
        long alreadyUsed = count(results, CouponUsageResult.AlreadyUsed.class);

        assertEquals(1, successes, "only one concurrent attempt for the same user may succeed");
        assertEquals(threads - 1, alreadyUsed, "the rest must be rejected as already used");
        assertEquals(threads, successes + alreadyUsed, "no activation may take any other path");

        assertEquals(1, currentUsage(couponId), "the counter must advance exactly once");
        assertEquals(1, usageCount(couponId), "exactly one usage row must survive");
    }

    @Test
    void exhaustedCouponLeavesNoOrphanUsageRow() throws Exception {
        long couponId = insertCoupon("ORPHN1", 1);
        long firstUser = 3_000_001L;
        long lateUser = 3_000_002L;

        // Exhaust the coupon...
        CouponUsageResult first = couponService.activateCoupon("ORPHN1", firstUser, TEST_IP);
        assertInstanceOf(CouponUsageResult.Success.class, first);

        // ...then a different user arrives once the cap is already reached.
        CouponUsageResult result = couponService.activateCoupon("ORPHN1", lateUser, TEST_IP);
        assertInstanceOf(CouponUsageResult.Exhausted.class, result,
                "an exhausted coupon must reject a new user with Exhausted, not succeed");

        assertEquals(0, usageCountForUser(couponId, lateUser),
                "the rolled-back attempt must leave no usage row for the rejected user");
        assertEquals(currentUsage(couponId), usageCount(couponId),
                "count(coupon_usage) must stay equal to current_usage - no orphaned rows");
    }

    /**
     * Runs one activation per user, all released at the same instant via a start gate so they contend
     * on the same coupon rows. Results are returned in submission order.
     */
    private List<CouponUsageResult> activateConcurrently(String code, List<Long> userIds) throws Exception {
        int n = userIds.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<CouponUsageResult>> futures = new ArrayList<>(n);
            for (long userId : userIds) {
                final long uid = userId;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return couponService.activateCoupon(code, uid, TEST_IP);
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "not all threads reached the start gate in time");
            start.countDown(); // release everyone at once
            List<CouponUsageResult> results = new ArrayList<>(n);
            for (Future<CouponUsageResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private long count(List<CouponUsageResult> results, Class<? extends CouponUsageResult> type) {
        return results.stream().filter(type::isInstance).count();
    }
}