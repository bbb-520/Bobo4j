package com.bbb.exercise.agentdemo.runtime.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 服务间共享的 CORS 策略（设计文档 §6.1「公共 Web 异常映射基础设施」同族）。
 *
 * <p>2026-10-01 Phase B 第 1 批：从 {@code com.bbb.exercise.agentdemo.chatservice.config.WebConfig}
 * 迁到合法公共包。目的<b>不是改行为</b>，而是让新服务只扫描
 * {@code com.bbb.exercise.agentdemo.runtime.web} 就能拿到它，
 * 从而不再需要「为了拿一个 CORS 配置而扫描整个遗留根包」（设计文档 §6.4）。
 *
 * <p>策略值与原实现逐字段一致，迁移不改变任何响应头。
 * <b>需要它的服务必须显式扫描本包</b>——它不再对「仅扫描遗留根包」的模块生效。
 */
@Configuration
public class WebConfig {
    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:https://www.boboo.xin,https://boboo.xin,http://localhost:*,http://127.0.0.1:*}}")
    private String allowedOrigins = "https://www.boboo.xin,https://boboo.xin,http://localhost:*,http://127.0.0.1:*";

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Content-Type", "X-Request-Id"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/api/**", c);
        return new CorsWebFilter(s);
    }
}
