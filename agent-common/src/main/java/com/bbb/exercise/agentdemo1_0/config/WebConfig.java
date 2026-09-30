package com.bbb.exercise.agentdemo1_0.config;
import org.springframework.context.annotation.*; import org.springframework.web.cors.CorsConfiguration; import org.springframework.web.cors.reactive.*; import java.util.List;
/** Shared gateway-compatible CORS policy for every reactive service. */
@Configuration public class WebConfig {
 @Bean public CorsWebFilter corsWebFilter(){CorsConfiguration c=new CorsConfiguration();c.setAllowedOriginPatterns(List.of("https://www.boboo.xin","https://boboo.xin","http://localhost:*","http://127.0.0.1:*"));c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("*"));c.setExposedHeaders(List.of("Content-Type","X-Request-Id"));c.setAllowCredentials(true);c.setMaxAge(3600L);UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/api/**",c);return new CorsWebFilter(s);}
}
