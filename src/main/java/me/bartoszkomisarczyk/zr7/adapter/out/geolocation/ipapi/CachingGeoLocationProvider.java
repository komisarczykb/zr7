package me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationUnresolvableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Primary
public class CachingGeoLocationProvider implements GeoLocationProvider {

    private final GeoLocationProvider delegate;
    private final Cache<String, CachedLookup> cache;

    public CachingGeoLocationProvider(
            @Qualifier("delegateGeoLocationProvider") GeoLocationProvider delegate,
            @Value("${geolocation.cache.ttl:2m}") Duration ttl,
            @Value("${geolocation.cache.negative-ttl:30s}") Duration negativeTtl,
            @Value("${geolocation.cache.max-size:1000}") long maxSize) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                // Failures are cached too, but far more briefly than successes: an unhealthy or
                // rate-limiting provider must not be re-hit once per request, and it must be given a
                // chance to recover well before a resolved country would go stale.
                .expireAfter(Expiry.creating(
                        (String ip, CachedLookup lookup) -> lookup.isFailure() ? negativeTtl : ttl))
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public GeoLocationResult resolve(String ip) {
        CachedLookup lookup = cache.get(ip, this::load);
        if (lookup.isFailure()) {
            throw replay(lookup.failure());
        }
        return lookup.result();
    }

    private CachedLookup load(String ip) {
        try {
            return CachedLookup.of(delegate.resolve(ip));
        } catch (GeoLocationException e) {
            return CachedLookup.of(e);
        }
    }

    /**
     * Rethrows a cached failure as a fresh exception of the same type - the concrete type decides how
     * long the caller is told to back off, so it has to survive the cache. A new instance per call
     * keeps the stack trace pointing at the caller instead of sharing one throwable across threads.
     */
    private static GeoLocationException replay(GeoLocationException cached) {
        String message = cached.getMessage();
        return switch (cached) {
            case GeoLocationRateLimitedException e -> new GeoLocationRateLimitedException(message, e);
            case GeoLocationUnresolvableException e -> new GeoLocationUnresolvableException(message, e);
            default -> new GeoLocationException(message, cached);
        };
    }

    /** Either a resolved country or the failure that resolving it produced; exactly one is non-null. */
    private record CachedLookup(GeoLocationResult result, GeoLocationException failure) {

        static CachedLookup of(GeoLocationResult result) {
            return new CachedLookup(result, null);
        }

        static CachedLookup of(GeoLocationException failure) {
            return new CachedLookup(null, failure);
        }

        boolean isFailure() {
            return failure != null;
        }
    }
}