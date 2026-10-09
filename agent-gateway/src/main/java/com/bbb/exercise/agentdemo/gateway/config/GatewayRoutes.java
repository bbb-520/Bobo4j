package com.bbb.exercise.agentdemo.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway 是唯一公网业务入口，只把 {@code /api/**} 路由到新服务的 discovery 名。
 *
 * <p>所有路由都指向当前领域服务，不保留迁移期兼容目标。
 */
@Configuration
public class GatewayRoutes {
    public static final String AUTH_SERVICE_ID = "agent-auth-service";
    public static final String CHAT_SERVICE_ID = "agent-chat-service";
    public static final String MEDIA_SERVICE_ID = "agent-media-service";
    public static final String CONTENT_SERVICE_ID = "agent-content-service";
    public static final String ORCHESTRATOR_SERVICE_ID = "agent-orchestrator-service";
    public static final String RAG_SERVICE_ID = "agent-rag-service";

    public static final String[] AUTH_PATHS = {"/api/auth/**", "/api/settings/keys/**", "/api/settings/models/**", "/api/billing/**", "/api/payments/**"};
    public static final String[] CHAT_PATHS = {"/api/chat/**", "/api/settings/visual-memory/**"};
    public static final String[] MEDIA_PATHS = {"/api/image-assets/**", "/api/image-jobs/**"};
    public static final String[] CONTENT_PATHS = {"/api/bobo/**", "/api/zine/**"};
    public static final String[] RAG_PATHS = {"/api/documents/**", "/api/document-conversations/**"};

    @Bean
    RouteLocator agentRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth", r -> r.path(AUTH_PATHS).uri(lb(AUTH_SERVICE_ID)))
                .route("chat", r -> r.path(CHAT_PATHS).uri(lb(CHAT_SERVICE_ID)))
                .route("images", r -> r.path(MEDIA_PATHS).uri(lb(MEDIA_SERVICE_ID)))
                .route("content", r -> r.path(CONTENT_PATHS).uri(lb(CONTENT_SERVICE_ID)))
                .route("orchestrator", r -> r.path("/api/agent-runs/**", "/api/agent-executions/**").uri(lb(ORCHESTRATOR_SERVICE_ID)))
                .route("documents", r -> r.path(RAG_PATHS).uri(lb(RAG_SERVICE_ID)))
                .build();
    }

    private static String lb(String serviceId) {
        return "lb://" + serviceId;
    }
}
