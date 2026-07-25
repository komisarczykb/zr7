package me.bartoszkomisarczyk.zr7.domain.geolocation;

public record GeoLocationResult(String countryCode) {
    public GeoLocationResult(String countryCode) {
        this.countryCode = countryCode.toUpperCase();
    }
}