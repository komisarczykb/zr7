package me.bartoszkomisarczyk.zr7.adapter.geolocation.ipapi;

import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationProvider;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationRateLimitedException;
import me.bartoszkomisarczyk.zr7.domain.geolocation.GeoLocationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;


/*
 * The API base path is
 * http://ip-api.com/json/{query}
 *
 * {query} can be a single IPv4/IPv6 address or a domain name. If you don't supply a query the current IP address will be used.
 *
 * Parameters
 * Query parameters (such as custom fields and JSONP callback) are appended as GET request parameters, for example:
 * http://ip-api.com/json/?fields=61439
 *
 * 	fields	response fields optional
 * 	lang	response language optional
 *
 * ===
 * 	name: status | description: success or fail | type: string
 * ---
 *	name: message | description: included only when status is fail - Can be one of the following:
 * private range, reserved range, invalid query | type: string
 * ---
 * name: countryCode | description: Two-letter country code ISO 3166-1 alpha-2 | type: string
 * ===
 * */

/*
 * This endpoint is limited to 45 requests per minute from an IP address.
 * If you go over the limit your requests will be throttled (HTTP 429) until your rate limit window is reset.
 * If you constantly go over the limit your IP address will be banned for 1 hour.
 * */
@Component
@ConditionalOnProperty(name = "geolocation.provider", havingValue = "ipapi", matchIfMissing = true)
public class IpApiGeoLocationProvider implements GeoLocationProvider {

    private final RestClient restClient;

    public IpApiGeoLocationProvider(RestClient ipApiRestClient) {
        this.restClient = ipApiRestClient;
    }

    @Override
    public GeoLocationResult resolve(String ip) {
        IpApiResponse response;
        try {
            response = restClient.get()
                    .uri("json/{ip}?fields=49154&lang=en")
                    .retrieve()
                    .body(IpApiResponse.class);
        } catch (HttpClientErrorException e) {
            // ip-api throttles with HTTP 429 (45 req/min free tier).
            if (e.getStatusCode().value() == 429) {
                throw new GeoLocationRateLimitedException("Geolocation provider rate limit exceeded", e);
            }
            throw new GeoLocationException(e.getMessage(), e);
        } catch (RestClientException e) {
            throw new GeoLocationException(e.getMessage(), e);
        }

        if (response == null || response.status() == null) {
            throw new GeoLocationException("Empty or malformed response from geolocation provider");
        }

        if (response.status().equalsIgnoreCase("fail")) {
            String errorMessage;
            switch (response.message()) {
                case "private range" -> errorMessage = "IP is within private range";
                case "reserved range" -> errorMessage = "IP is within reserved range";
                case "invalid query" -> errorMessage = String.format("%s: %s", response.message(), response.query());
                default -> errorMessage = response.message();
            }
            throw new GeoLocationException(errorMessage);
        }

        return new GeoLocationResult(response.countryCode());
    }

}