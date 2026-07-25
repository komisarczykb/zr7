package me.bartoszkomisarczyk.zr7.adapter.geolocation.ipapi;

public record IpApiResponse(
        String status,
        String message,
        String countryCode
) { }