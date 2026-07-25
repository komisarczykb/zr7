package me.bartoszkomisarczyk.zr7.domain.geolocation;

public interface GeoLocationProvider {
    GeoLocationResult resolve(String ip);
}