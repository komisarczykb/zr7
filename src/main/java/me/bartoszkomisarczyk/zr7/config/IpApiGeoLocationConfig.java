package me.bartoszkomisarczyk.zr7.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class IpApiGeoLocationConfig {

    @Bean
    @ConditionalOnProperty(name = "geolocation.provider", havingValue = "ipapi", matchIfMissing = true)
    public RestClient ipApiRestClient(RestClient.Builder builder,
                                      @Value("${geolocation.ipapi.base-url:http://ip-api.com/}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}