package me.bartoszkomisarczyk.zr7.adapter.geolocation.ipapi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class IpApiGeoLocationConfig {
    @Bean
    @ConditionalOnProperty(name = "geolocation.provider", havingValue = "ipapi")
    public RestClient ipApiRestClient(RestClient.Builder builder) {
        return builder.baseUrl("http://ip-api.com/").build();
    }
}