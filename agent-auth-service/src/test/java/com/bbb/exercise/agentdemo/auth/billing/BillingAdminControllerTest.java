package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BillingAdminControllerTest {
    @Test
    void aLoggedInUserCannotGrantThemselvesReconciliationPermission() {
        var identity = new ChatIdentity("local", "ordinary-user", true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/billing/admin/usages/victim/usage/refund"));
        var auth = mock(AuthService.class);
        var identities = mock(AuthIdentityResolver.class);
        var billing = mock(BillingService.class);
        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(identity));
        when(auth.publicUserId(identity)).thenReturn("ordinary-user");
        var controller = new BillingAdminController(auth, identities, billing, "operator-user");

        assertThatThrownBy(() -> controller.refund("victim", "usage",
                new BillingAdminController.Reason("attempted adjustment"), exchange).block())
                .isInstanceOf(BillingException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BillingException) error).getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(billing);
    }

    @Test
    void anAllowlistedOperatorIsRecordedAsTheActor() {
        var identity = new ChatIdentity("local", "operator-user", true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/billing/admin/usages/victim/usage/refund"));
        var auth = mock(AuthService.class);
        var identities = mock(AuthIdentityResolver.class);
        var billing = mock(BillingService.class);
        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(identity));
        when(auth.publicUserId(identity)).thenReturn("operator-user");
        var controller = new BillingAdminController(auth, identities, billing, " operator-user ");
        controller.refund("victim", "usage", new BillingAdminController.Reason("provider confirmed failure"), exchange).block();

        verify(billing).reconcileRefund("victim", "usage", "operator-user", "provider confirmed failure");
    }
}
