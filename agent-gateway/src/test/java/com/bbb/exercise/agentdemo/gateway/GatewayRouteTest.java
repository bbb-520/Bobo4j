package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.config.GatewayRoutes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteTest {
    @Test
    void declaresRoutesForCoreDomains() {
        assertThat(GatewayRoutes.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("agentRoutes"));
    }

    @Test
    void routesMatchThePublicControllerPrefixes() {
        assertThat(Arrays.asList(GatewayRoutes.AUTH_PATHS))
                .contains("/api/auth/**", "/api/settings/keys/**", "/api/settings/models/**")
                .doesNotContain("/api/settings/**");
        assertThat(Arrays.asList(GatewayRoutes.CHAT_PATHS))
                .contains("/api/chat/**", "/api/settings/visual-memory/**", "/api/zine/**");
        assertThat(Arrays.asList(GatewayRoutes.MEDIA_PATHS))
                .contains("/api/image-assets/**", "/api/image-jobs/**")
                .doesNotContain("/api/images/**");
        assertThat(Arrays.asList(GatewayRoutes.CONTENT_PATHS))
                .containsExactly("/api/bobo/**");
    }
}
