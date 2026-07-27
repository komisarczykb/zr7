package me.bartoszkomisarczyk.zr7.adapter.out.geolocation.ipapi;

public record IpApiResponse(
        String status,
        String message,
        String query,
        String countryCode
) { }