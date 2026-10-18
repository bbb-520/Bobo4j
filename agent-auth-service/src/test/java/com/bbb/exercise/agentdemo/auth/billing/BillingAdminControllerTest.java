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

/** 验证计费调整权限来自真实授权，并将授权操作者记入审计。 */
class BillingAdminControllerTest {
    @Test
    void aLoggedInUserCannotGrantThemselvesReconciliationPermission() {
        var identity = new ChatIdentity("local", "ordinary-user", true);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(
                                "/api/billing/admin/usages/victim/usage/refund"));
        var auth = mock(AuthService.class);
        var identities = mock(AuthIdentityResolver.class);
        var billing = mock(BillingService.class);

        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(identity));
        when(auth.publicUserId(identity)).thenReturn("ordinary-user");
        var rbac = mock(com.bbb.exercise.agentdemo.auth.rbac.RbacService.class);

        doThrow(new AuthService.AuthException(403, "缺少权限"))
                .when(rbac)
                .require(anyLong(), eq("billing:refund"));
        var controller = new BillingAdminController(auth, identities, billing, rbac);

        assertThatThrownBy(
                        () ->
                                controller
                                        .refund(
                                                "victim",
                                                "usage",
                                                new BillingAdminController.Reason(
                                                        "attempted adjustment"),
                                                exchange)
                                        .block())
                .isInstanceOf(AuthService.AuthException.class)
                .satisfies(
                        error ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((AuthService.AuthException) error).status())
                                        .isEqualTo(403));
        verifyNoInteractions(billing);
    }

    @Test
    void anAuthorizedOperatorIsRecordedAsTheActor() {
        var identity = new ChatIdentity("local", "operator-user", true);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(
                                "/api/billing/admin/usages/victim/usage/refund"));
        var auth = mock(AuthService.class);
        var identities = mock(AuthIdentityResolver.class);
        var billing = mock(BillingService.class);

        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(identity));
        when(auth.publicUserId(identity)).thenReturn("operator-user");
        var rbac = mock(com.bbb.exercise.agentdemo.auth.rbac.RbacService.class);
        var controller = new BillingAdminController(auth, identities, billing, rbac);

        controller
                .refund(
                        "victim",
                        "usage",
                        new BillingAdminController.Reason("provider confirmed failure"),
                        exchange)
                .block();

        verify(billing)
                .reconcileRefund("victim", "usage", "operator-user", "provider confirmed failure");
        verify(rbac).require(anyLong(), eq("billing:refund"));
    }
}
