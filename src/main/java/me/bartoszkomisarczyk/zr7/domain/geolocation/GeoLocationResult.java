package me.bartoszkomisarczyk.zr7.domain.geolocation;

import java.util.Locale;

public record GeoLocationResult(String countryCode) {
    public GeoLocationResult {
        countryCode = countryCode.toUpperCase(Locale.ROOT);
    }
}