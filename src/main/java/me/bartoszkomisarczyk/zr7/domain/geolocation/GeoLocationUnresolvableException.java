package me.bartoszkomisarczyk.zr7.domain.geolocation;

/**
 * The provider answered normally but the IP itself cannot be mapped to a country (private range,
 * reserved range, malformed query). Distinct from the provider being unhealthy: this is bad input,
 * not an upstream failure, so it must not count against the circuit breaker.
 */
public class GeoLocationUnresolvableException extends GeoLocationException {
    public GeoLocationUnresolvableException(String message) {
        super(message);
    }

    public GeoLocationUnresolvableException(String message, Throwable cause) {
        super(message, cause);
    }
}