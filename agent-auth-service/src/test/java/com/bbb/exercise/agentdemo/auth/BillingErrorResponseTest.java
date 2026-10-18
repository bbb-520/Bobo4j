package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.auth.billing.BillingException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class BillingErrorResponseTest {
    @Test void paymentFailureRetainsAnActionableMessageWithoutExposingItsCause() {
        WebTestClient.bindToController(new FailureController())
                .controllerAdvice(new AuthExceptionHandler()).build()
                .get().uri("/failure").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo(503)
                .jsonPath("$.message").isEqualTo("支付宝应用尚未上线，请联系管理员")
                .jsonPath("$.cause").doesNotExist()
                .jsonPath("$.trace").doesNotExist();
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure") String fail() {
            throw new BillingException(503, "支付宝应用尚未上线，请联系管理员",
                    new IllegalStateException("private provider diagnostics"));
        }
    }
}
