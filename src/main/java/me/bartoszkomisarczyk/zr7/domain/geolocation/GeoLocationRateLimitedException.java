package me.bartoszkomisarczyk.zr7.domain.geolocation;

public class GeoLocationRateLimitedException extends GeoLocationException {
    public GeoLocationRateLimitedException(String message) {
        super(message);
    }

    public GeoLocationRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}