package me.bartoszkomisarczyk.zr7.adapter.geolocation.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Primary
public class CachingGeoLocationProvider implements GeoLocationProvider {

    private final GeoLocationProvider delegate;
    private final Cache<String, GeoLocationResult> cache;

    public CachingGeoLocationProvider(
            @Qualifier("delegateGeoLocationProvider") GeoLocationProvider delegate,
            @Value("${geolocation.cache.ttl:2m}") Duration ttl,
            @Value("${geolocation.cache.max-size:1000}") long maxSize) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public GeoLocationResult resolve(String ip) {
        return cache.get(ip, delegate::resolve);
    }
}