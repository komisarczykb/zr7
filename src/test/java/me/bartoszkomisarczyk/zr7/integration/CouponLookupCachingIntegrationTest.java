package me.bartoszkomisarczyk.zr7.integration;

import me.bartoszkomisarczyk.zr7.adapter.coupon.JdbcCouponRepository;
import me.bartoszkomisarczyk.zr7.adapter.coupon.cache.CachingCouponRepository;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Once a coupon code has been resolved once, its {@code CouponLookup} (id + country) is permanent -
 * no endpoint changes it - so repeat activations for the same code must not re-query the database.
 */
class CouponLookupCachingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CouponRepository couponRepository; // resolves to @Primary CachingCouponRepository

    @MockitoSpyBean
    private JdbcCouponRepository delegate;

    @Test
    void injectedRepositoryIsTheCachingDecorator() {
        assertInstanceOf(CachingCouponRepository.class, couponRepository);
    }

    @Test
    void secondLookupForSameCodeIsServedFromCache() throws Exception {
        insertCoupon("LOOKUP1", 5);
        insertCoupon("LOOKUP2", 5);

        var first = couponRepository.findLookupByCode("LOOKUP1");
        var second = couponRepository.findLookupByCode("LOOKUP1"); // served from cache
        var other = couponRepository.findLookupByCode("LOOKUP2");

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertTrue(other.isPresent());
        verify(delegate, times(1)).findLookupByCode("LOOKUP1"); // upstream hit once for the repeated code
        verify(delegate, times(1)).findLookupByCode("LOOKUP2");
    }

    @Test
    void lookupIsCaseInsensitiveAcrossCacheHits() throws Exception {
        insertCoupon("LOOKUP3", 5);

        couponRepository.findLookupByCode("lookup3");
        couponRepository.findLookupByCode("LOOKUP3");

        // the decorator normalizes the key before hitting the delegate, regardless of caller casing
        verify(delegate, times(1)).findLookupByCode("LOOKUP3");
    }
}