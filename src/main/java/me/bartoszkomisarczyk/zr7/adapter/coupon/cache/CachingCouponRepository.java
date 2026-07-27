package me.bartoszkomisarczyk.zr7.adapter.coupon.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCode;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Primary
public class CachingCouponRepository implements CouponRepository {

    private final CouponRepository delegate;
    private final Cache<String, CouponLookup> cache;

    public CachingCouponRepository(
            @Qualifier("delegateCouponRepository") CouponRepository delegate,
            @Value("${coupon.lookup-cache.ttl:30m}") Duration ttl,
            @Value("${coupon.lookup-cache.max-size:10000}") long maxSize) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public Optional<CouponLookup> findByCode(String code) {
        return Optional.ofNullable(
                cache.get(CouponCode.normalize(code), c -> delegate.findByCode(c).orElse(null)));
    }

    @Override
    public Optional<Coupon> findFullByCode(String code) {
        // Deliberately not cached: currentUsage changes on every activation, so a caller reading
        // a coupon back needs the live value from the delegate, not a stale cached snapshot.
        return delegate.findFullByCode(code);
    }

    @Override
    public Coupon insert(String code, int maxUsage, String countryCode) {
        return delegate.insert(code, maxUsage, countryCode);
    }

    @Override
    public int insertUsage(long couponId, long userId) {
        return delegate.insertUsage(couponId, userId);
    }

    @Override
    public int incrementUsage(long couponId) {
        return delegate.incrementUsage(couponId);
    }
}