package com.bbb.exercise.agentdemo.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真正构建一次路由表，确认每条路由指向 owner 服务。
 * 测试与 {@link GatewayRoutes} 同包，因此可直接调用包内可见的 {@code agentRoutes}。
 */
class GatewayRoutesServiceIdTest {

    @Test
    void everyRouteTargetsTheOwningServiceAndNeverLegacy() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // RouteLocatorBuilder 通过 ApplicationContext 解析断言工厂，最小上下文即可。
            context.registerBean(WebFluxProperties.class);
            context.registerBean(PathRoutePredicateFactory.class);
            context.refresh();
            Map<String, String> routes = new GatewayRoutes()
                    .agentRoutes(new RouteLocatorBuilder(context))
                    .getRoutes()
                    .collect(Collectors.toMap(Route::getId, route -> route.getUri().toString()))
                    .block();

            assertThat(routes).isNotNull();
            assertThat(routes).containsEntry("auth", "lb://" + GatewayRoutes.AUTH_SERVICE_ID);
            assertThat(routes).containsEntry("chat", "lb://" + GatewayRoutes.CHAT_SERVICE_ID);
            assertThat(routes).containsEntry("images", "lb://" + GatewayRoutes.MEDIA_SERVICE_ID);
            assertThat(routes).containsEntry("content", "lb://" + GatewayRoutes.CONTENT_SERVICE_ID);
            assertThat(routes).containsEntry("orchestrator", "lb://" + GatewayRoutes.ORCHESTRATOR_SERVICE_ID);
            assertThat(routes).containsEntry("chat", "lb://agent-chat-service");
        }
    }
}
