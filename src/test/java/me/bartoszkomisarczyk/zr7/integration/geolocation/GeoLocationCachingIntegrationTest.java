package me.bartoszkomisarczyk.zr7.integration.geolocation;

import me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi.CachingGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi.IpApiGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationUnresolvableException;
import me.bartoszkomisarczyk.zr7.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GeoLocationCachingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GeoLocationProvider geoLocationProvider; // resolves to @Primary CachingGeoLocationProvider

    @MockitoSpyBean
    private IpApiGeoLocationProvider delegate;

    @Test
    void injectedPortIsTheCachingDecorator() {
        assertInstanceOf(CachingGeoLocationProvider.class, geoLocationProvider);
    }

    @Test
    void secondResolveForSameIpIsServedFromCache() {
        doReturn(new GeoLocationResult("US")).when(delegate).resolve("8.8.8.8");
        doReturn(new GeoLocationResult("PL")).when(delegate).resolve("1.1.1.1");

        GeoLocationResult first = geoLocationProvider.resolve("8.8.8.8");
        GeoLocationResult second = geoLocationProvider.resolve("8.8.8.8"); // served from cache
        GeoLocationResult other = geoLocationProvider.resolve("1.1.1.1");

        assertEquals("US", first.countryCode());
        assertEquals("US", second.countryCode());
        assertEquals("PL", other.countryCode());
        verify(delegate, times(1)).resolve("8.8.8.8"); // upstream hit once for the repeated IP
        verify(delegate, times(1)).resolve("1.1.1.1");
    }

    @Test
    void failuresAreCachedSoAFailingProviderIsNotRehit() {
        // IPs are unique across the whole suite: the Caffeine cache is a singleton shared by every test
        // class in the context, so a key another test warmed would break the hit-count assertion.
        doThrow(new GeoLocationRateLimitedException("rate limited")).when(delegate).resolve("198.51.100.7");
        doThrow(new GeoLocationUnresolvableException("IP is within private range"))
                .when(delegate).resolve("198.51.100.8");

        // The concrete exception type survives the cache - it is what decides the Retry-After the caller gets.
        assertThrows(GeoLocationRateLimitedException.class, () -> geoLocationProvider.resolve("198.51.100.7"));
        assertThrows(GeoLocationRateLimitedException.class, () -> geoLocationProvider.resolve("198.51.100.7"));
        assertThrows(GeoLocationUnresolvableException.class, () -> geoLocationProvider.resolve("198.51.100.8"));
        assertThrows(GeoLocationUnresolvableException.class, () -> geoLocationProvider.resolve("198.51.100.8"));

        verify(delegate, times(1)).resolve("198.51.100.7"); // the repeat never reached the provider
        verify(delegate, times(1)).resolve("198.51.100.8");
    }

    @Test
    void cachedFailureKeepsTheOriginalMessageAndCause() {
        GeoLocationException original = new GeoLocationException("upstream exploded");
        doThrow(original).when(delegate).resolve("198.51.100.9");

        assertThrows(GeoLocationException.class, () -> geoLocationProvider.resolve("198.51.100.9"));
        GeoLocationException replayed =
                assertThrows(GeoLocationException.class, () -> geoLocationProvider.resolve("198.51.100.9"));

        assertEquals("upstream exploded", replayed.getMessage());
        assertEquals(original, replayed.getCause());
    }
}