package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;


/**
 * WebFlux 跨域配置：允许本机前端（Vite 开发服务器等）直连后端接口。
 */
@Configuration
public class WebConfig {

    private static final List<String> ALLOWED_ORIGIN_PATTERNS =
            List.of("https://www.boboo.xin", "https://boboo.xin", "http://localhost:*", "http://127.0.0.1:*");

    /** 注册响应式 CORS 过滤器，规则作用于 {@code /api/**} */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
        // /api/settings/keys 使用 PUT；缺少 PUT 会让浏览器的 CORS 预检失败，
        // 表面上看起来像“密钥保存失败”，并且后续刷新时无法进入在线模式。
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type", "X-Request-Id"));
        // /api/chat 使用 HttpOnly 匿名 Cookie 绑定会话归属；跨域前端必须携带凭据。
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}
