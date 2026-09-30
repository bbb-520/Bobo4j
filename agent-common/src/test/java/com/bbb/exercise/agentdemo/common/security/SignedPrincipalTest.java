package com.bbb.exercise.agentdemo.common.security;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedPrincipalTest {
    private static final byte[] SECRET = new byte[32];

    @Test
    void signsAndVerifiesShortLivedPrincipal() {
        String token = SignedPrincipal.issue("agent-chat", "user:7", "local", Instant.ofEpochSecond(200), SECRET);
        var principal = SignedPrincipal.verify(token, SECRET, Instant.ofEpochSecond(100));
        assertThat(principal.subject()).isEqualTo("user:7");
        assertThat(principal.tenant()).isEqualTo("local");
    }

    @Test
    void rejectsTamperingAndExpiry() {
        String token = SignedPrincipal.issue("agent-chat", "user:7", "local", Instant.ofEpochSecond(200), SECRET);
        assertThatThrownBy(() -> SignedPrincipal.verify(token + "x", SECRET, Instant.ofEpochSecond(100))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignedPrincipal.verify(token, SECRET, Instant.ofEpochSecond(200))).isInstanceOf(IllegalArgumentException.class);
    }
}
