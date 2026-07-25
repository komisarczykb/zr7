package me.bartoszkomisarczyk.zr7.integration;

import me.bartoszkomisarczyk.zr7.adapter.geolocation.cache.CachingGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.adapter.geolocation.ipapi.IpApiGeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doReturn;
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
}