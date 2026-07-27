package me.bartoszkomisarczyk.zr7.application.coupon;

import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponCreationResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;

@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final int RATE_LIMITED_RETRY_AFTER_SECONDS = 60;
    private static final int PROVIDER_FAILURE_RETRY_AFTER_SECONDS = 5;

    private final CouponRepository couponRepository;
    private final TransactionTemplate transactionTemplate;
    private final GeoLocationProvider geoLocationProvider;
    private final CouponExhaustionCache exhaustionCache;

    public CouponService(CouponRepository couponRepository,
                         PlatformTransactionManager transactionManager,
                         GeoLocationProvider geoLocationProvider,
                         CouponExhaustionCache exhaustionCache) {
        this.couponRepository = couponRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.geoLocationProvider = geoLocationProvider;
        this.exhaustionCache = exhaustionCache;
    }

    public CouponCreationResult createCoupon(String code, int maxUsage, String countryCode) {
        try {
            return new CouponCreationResult.Success(
                    couponRepository.insert(code, maxUsage, countryCode.toUpperCase(Locale.ROOT)));
        } catch (DuplicateKeyException e) {
            // unique_coupon_code_upper - case-insensitive code collision
            return new CouponCreationResult.Conflict("Coupon code already exists");
        }
    }

    public Optional<Coupon> findCoupon(String code) {
        return couponRepository.findByCode(code);
    }

    public CouponUsageResult activateCoupon(String code, long userId, String userIp) {
        Optional<CouponLookup> coupon = couponRepository.findLookupByCode(code);
        if (coupon.isEmpty()) {
            return new CouponUsageResult.NotFound();
        }

        CouponUsageResult countryResolutionFailure;
        String userCountry;
        try {
            userCountry = geoLocationProvider.resolve(userIp).countryCode();
            countryResolutionFailure = null;
        } catch (GeoLocationRateLimitedException e) {
            log.warn("Geolocation provider rate-limited while resolving IP for coupon {}", code, e);
            userCountry = null;
            countryResolutionFailure = new CouponUsageResult.GeoLocationUnavailable(RATE_LIMITED_RETRY_AFTER_SECONDS);
        } catch (GeoLocationException e) {
            log.warn("Geolocation provider failed while resolving IP for coupon {}", code, e);
            userCountry = null;
            countryResolutionFailure =
                    new CouponUsageResult.GeoLocationUnavailable(PROVIDER_FAILURE_RETRY_AFTER_SECONDS);
        }

        if (countryResolutionFailure != null) {
            return countryResolutionFailure;
        }
        if (!userCountry.equals(coupon.get().countryCode())) {
            return new CouponUsageResult.CountryNotAllowed();
        }

        // Precedence: not-found, then country, then exhaustion, then already-used — checked in
        // that fixed order so the same request against the same DB state always yields the same
        // rejection reason, regardless of whether the exhaustion cache happens to be warm.
        if (exhaustionCache.isExhausted(code)) {
            return new CouponUsageResult.Exhausted();
        }

        return transactionTemplate.execute(status -> registerUsage(code, coupon.get().id(), userId, status));
    }

    private CouponUsageResult registerUsage(String code, long couponId, long userId, TransactionStatus status) {
        if (couponRepository.insertUsage(couponId, userId) == 0) {
            // unique_single_use conflict
            return new CouponUsageResult.AlreadyUsed();
        }
        if (couponRepository.incrementUsage(couponId) == 0) {
            //roll back the usage row so count(coupon_usage) == current_usage, only if could not increment current_usage
            status.setRollbackOnly();
            exhaustionCache.markExhausted(code);
            return new CouponUsageResult.Exhausted();
        }
        return new CouponUsageResult.Success();
    }
}