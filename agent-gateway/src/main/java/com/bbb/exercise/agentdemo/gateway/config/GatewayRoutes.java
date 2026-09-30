package com.bbb.exercise.agentdemo.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    public static final String[] AUTH_PATHS = {"/api/auth/**", "/api/settings/keys/**", "/api/settings/models/**"};
    public static final String[] CHAT_PATHS = {"/api/chat/**", "/api/settings/visual-memory/**", "/api/zine/**"};
    public static final String[] MEDIA_PATHS = {"/api/image-assets/**", "/api/image-jobs/**"};
    public static final String[] CONTENT_PATHS = {"/api/bobo/**"};

    @Bean
    RouteLocator agentRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth", r -> r.path(AUTH_PATHS).uri("lb://agent-auth-service"))
                .route("chat", r -> r.path(CHAT_PATHS).uri("lb://agent-chat-service"))
                .route("images", r -> r.path(MEDIA_PATHS).uri("lb://agent-media-service"))
                .route("content", r -> r.path(CONTENT_PATHS).uri("lb://agent-content-service"))
                .route("orchestrator", r -> r.path("/api/agent-runs/**").uri("lb://agent-orchestrator-service"))
                .build();
    }
}
