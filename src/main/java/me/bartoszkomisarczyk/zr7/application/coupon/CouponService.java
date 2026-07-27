package me.bartoszkomisarczyk.zr7.application.coupon;

import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponUsageResult.*;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final TransactionTemplate transactionTemplate;
    private final GeoLocationProvider geoLocationProvider;

    public CouponService(CouponRepository couponRepository,
                         PlatformTransactionManager transactionManager,
                         GeoLocationProvider geoLocationProvider) {
        this.couponRepository = couponRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.geoLocationProvider = geoLocationProvider;
    }

    public CouponUsageResult activateCoupon(String code, long userId, String userIp) {
        Optional<CouponLookup> coupon = couponRepository.findByCode(code);
        if (coupon.isEmpty()) {
            return new NotFound();
        }

        //unresolvable IP is treated as not allowed.
        String userCountry = resolveCountry(userIp);
        if (userCountry == null || !userCountry.equals(coupon.get().countryCode())) {
            return new CountryNotAllowed();
        }

        return transactionTemplate.execute(status -> registerUsage(coupon.get().id(), userId, status));
    }

    private CouponUsageResult registerUsage(long couponId, long userId, TransactionStatus status) {
        if (couponRepository.insertUsage(couponId, userId) == 0) {
            // unique_single_use conflict
            return new AlreadyUsed();
        }
        if (couponRepository.incrementUsage(couponId) == 0) {
            //roll back the usage row so count(coupon_usage) == current_usage, only if could not increment current_usage
            status.setRollbackOnly();
            return new Exhausted();
        }
        return new Success();
    }

    private String resolveCountry(String userIp) {
        try {
            return geoLocationProvider.resolve(userIp).countryCode();
        } catch (GeoLocationException e) {
            return null;
        }
    }
}