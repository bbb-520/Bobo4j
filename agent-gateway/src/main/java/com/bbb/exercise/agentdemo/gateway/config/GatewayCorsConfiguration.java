package com.bbb.exercise.agentdemo.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/** CORS policy for browser requests handled by the edge gateway. */
@Configuration
public class GatewayCorsConfiguration {
    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:https://www.boboo.xin,https://boboo.xin,http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins = "https://www.boboo.xin,https://boboo.xin,http://localhost:*,http://127.0.0.1:*";

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Type", "X-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return new CorsWebFilter(source);
    }
}
