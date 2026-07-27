package me.bartoszkomisarczyk.zr7.application.coupon;

import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCreationResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fast, Docker-free coverage of {@link CouponService}'s branching: every {@link CouponUsageResult}
 * and {@link CouponCreationResult} variant, driven purely through mocked collaborators. The
 * Testcontainers-based integration tests remain the source of truth for real DB behaviour
 * (concurrency, constraints); this class exists so the business-rule branching itself doesn't
 * require a container to exercise.
 */
class CouponServiceTest {

    private static final String CODE = "SUMMER1";
    private static final long USER_ID = 42L;
    private static final String USER_IP = "203.0.113.10";

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private GeoLocationProvider geoLocationProvider;
    @Mock
    private CouponExhaustionCache exhaustionCache;
    @Mock
    private TransactionStatus transactionStatus;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        couponService = new CouponService(couponRepository, transactionManager, geoLocationProvider, exhaustionCache);
    }

    // --- createCoupon ---

    @Test
    void createCouponReturnsSuccessOnInsert() {
        Coupon inserted = new Coupon(1L, CODE, Instant.now(), 5, 0, "PL");
        when(couponRepository.insert(CODE, 5, "PL")).thenReturn(inserted);

        CouponCreationResult result = couponService.createCoupon(CODE, 5, "pl");

        assertThat(result).isInstanceOf(CouponCreationResult.Success.class);
        assertThat(((CouponCreationResult.Success) result).coupon()).isEqualTo(inserted);
    }

    @Test
    void createCouponReturnsConflictOnDuplicateKey() {
        when(couponRepository.insert(any(), anyInt(), any())).thenThrow(new DuplicateKeyException("dup"));

        CouponCreationResult result = couponService.createCoupon(CODE, 5, "PL");

        assertThat(result).isInstanceOf(CouponCreationResult.Conflict.class);
    }

    // --- activateCoupon: not found ---

    @Test
    void activateReturnsNotFoundWhenCouponMissing() {
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.empty());

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.NotFound.class);
        verifyNoInteractions(geoLocationProvider, exhaustionCache);
    }

    // --- activateCoupon: country gate ---

    @Test
    void activateReturnsCountryNotAllowedOnMismatch() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenReturn(new GeoLocationResult("DE"));

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.CountryNotAllowed.class);
        verifyNoInteractions(exhaustionCache);
    }

    @Test
    void activateReturnsGeoLocationUnavailableOnRateLimit() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenThrow(new GeoLocationRateLimitedException("limited"));

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.GeoLocationUnavailable.class);
        assertThat(((CouponUsageResult.GeoLocationUnavailable) result).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void activateReturnsGeoLocationUnavailableOnGenericProviderFailure() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenThrow(new GeoLocationException("boom"));

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.GeoLocationUnavailable.class);
        assertThat(((CouponUsageResult.GeoLocationUnavailable) result).retryAfterSeconds()).isEqualTo(5);
    }

    // --- activateCoupon: exhaustion gate, checked after country but before the transaction ---

    @Test
    void activateReturnsExhaustedWhenCacheMarksItExhausted() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenReturn(new GeoLocationResult("PL"));
        when(exhaustionCache.isExhausted(CODE)).thenReturn(true);

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.Exhausted.class);
        verifyNoInteractions(transactionManager);
    }

    // --- activateCoupon: registerUsage paths (already-used, exhausted-on-write, success) ---

    @Test
    void activateReturnsAlreadyUsedWhenUsageInsertIsIgnored() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenReturn(new GeoLocationResult("PL"));
        when(exhaustionCache.isExhausted(CODE)).thenReturn(false);
        when(couponRepository.insertUsage(1L, USER_ID)).thenReturn(0);

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.AlreadyUsed.class);
        verify(couponRepository, never()).incrementUsage(anyLong());
    }

    @Test
    void activateReturnsExhaustedAndRollsBackWhenIncrementAffectsNoRows() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenReturn(new GeoLocationResult("PL"));
        when(exhaustionCache.isExhausted(CODE)).thenReturn(false);
        when(couponRepository.insertUsage(1L, USER_ID)).thenReturn(1);
        when(couponRepository.incrementUsage(1L)).thenReturn(0);

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.Exhausted.class);
        verify(transactionStatus).setRollbackOnly();
        verify(exhaustionCache).markExhausted(CODE);
    }

    @Test
    void activateReturnsSuccessWhenUsageIsRecordedAndIncremented() {
        stubCouponLookup("PL");
        when(geoLocationProvider.resolve(USER_IP)).thenReturn(new GeoLocationResult("PL"));
        when(exhaustionCache.isExhausted(CODE)).thenReturn(false);
        when(couponRepository.insertUsage(1L, USER_ID)).thenReturn(1);
        when(couponRepository.incrementUsage(1L)).thenReturn(1);

        CouponUsageResult result = couponService.activateCoupon(CODE, USER_ID, USER_IP);

        assertThat(result).isInstanceOf(CouponUsageResult.Success.class);
        verify(transactionStatus, never()).setRollbackOnly();
    }

    private void stubCouponLookup(String countryCode) {
        when(couponRepository.findByCode(CODE)).thenReturn(Optional.of(new CouponLookup(1L, countryCode)));
    }
}
