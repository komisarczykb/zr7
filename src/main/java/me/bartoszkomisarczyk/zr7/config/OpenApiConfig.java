package me.bartoszkomisarczyk.zr7.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI couponApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ZR7 Coupon API")
                        .description("Creation, lookup and country-restricted activation of discount coupons.")
                        .version("1.0.0"));
    }
}