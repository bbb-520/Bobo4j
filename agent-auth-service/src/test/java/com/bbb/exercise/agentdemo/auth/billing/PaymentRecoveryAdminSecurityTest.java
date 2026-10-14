package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.*;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.rbac.RbacService;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentRecoveryAdminSecurityTest {
    @Configuration @EnableWebFlux
    @Import({BrowserSecurityConfiguration.class,PaymentRecoveryAdminController.class,AuthExceptionHandler.class})
    static class Config {
        @Bean @Primary BrowserIdentityProvider identities() {
            return token->Mono.just(new BrowserIdentityProvider.BrowserUser("admin","test",Set.of("ADMIN"),
                    "admin".equals(token)?Set.of("billing:reconcile"):Set.of("payment:manage")));
        }
        @Bean AuthService auth() {
            var auth=mock(AuthService.class);when(auth.requireUserId(any())).thenReturn(1L);when(auth.publicUserId(any(ChatIdentity.class))).thenReturn("operator");return auth;
        }
        @Bean AuthIdentityResolver resolver() {
            var resolver=mock(AuthIdentityResolver.class);when(resolver.resolveRequired(any())).thenReturn(Mono.just(new ChatIdentity("test","admin",true)));return resolver;
        }
        @Bean RbacService rbac() {return mock(RbacService.class);}
        @Bean PaymentService orders() {return mock(PaymentService.class);}
    }
    @Test void adminRecoveryUsesPermissionPolicyCsrfAndTrustedServerPermission() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var client=WebTestClient.bindToApplicationContext(context).configureClient().build();
            var orders=context.getBean(PaymentService.class);var rbac=context.getBean(RbacService.class);
            clearInvocations(orders); // Spring invokes @PostConstruct on the service mock during setup.
            String path="/api/billing/admin/payment-orders/order";
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).cookie("bbb_agent_session","user").exchange().expectStatus().isForbidden();
            client.post().uri(path+"/resume").cookie("bbb_agent_session","user").cookie("XSRF-TOKEN","test-token").header("X-XSRF-TOKEN","test-token")
                    .bodyValue(new PaymentRecoveryAdminController.Reason("checked")).exchange().expectStatus().isForbidden();
            client.post().uri(path+"/resume").cookie("bbb_agent_session","admin").bodyValue(new PaymentRecoveryAdminController.Reason("checked"))
                    .exchange().expectStatus().isForbidden();
            verifyNoInteractions(orders);
            client.get().uri(path).cookie("bbb_agent_session","admin").exchange().expectStatus().isOk();
            client.post().uri(path+"/resume").cookie("bbb_agent_session","admin").cookie("XSRF-TOKEN","test-token").header("X-XSRF-TOKEN","test-token")
                    .bodyValue(new PaymentRecoveryAdminController.Reason("checked")).exchange().expectStatus().isOk();
            verify(orders).resumeReconciliation("order","operator","checked");
            doThrow(new AuthService.AuthException(403,"无权限")).when(rbac).require(1L,"billing:reconcile");
            client.get().uri(path).cookie("bbb_agent_session","admin").exchange().expectStatus().isForbidden();
            verify(orders,times(1)).find("order");
        }
    }
}
