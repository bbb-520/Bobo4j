package com.bbb.exercise.agentdemo.common.security;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedPrincipalTest {
    private static final byte[] SECRET = new byte[32];

    @Test
    void signsAndVerifiesShortLivedPrincipal() {
        String token = SignedPrincipal.issueScoped("k1", "agent-chat", "user:7", "local", "agent-auth", "session", Instant.ofEpochSecond(100), SECRET);
        var principal = SignedPrincipal.verifyScoped(token, Map.of("k1", SECRET), Instant.ofEpochSecond(101), "agent-auth", "session");
        assertThat(principal.subject()).isEqualTo("user:7");
        assertThat(principal.tenant()).isEqualTo("local");
    }

    @Test
    void rejectsTamperingAndExpiry() {
        String token = SignedPrincipal.issueScoped("k1", "agent-chat", "user:7", "local", "agent-auth", "session", Instant.ofEpochSecond(100), SECRET);
        assertThatThrownBy(() -> SignedPrincipal.verifyScoped(token + "x", Map.of("k1", SECRET), Instant.ofEpochSecond(101), "agent-auth", "session")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignedPrincipal.verifyScoped(token, Map.of("k1", SECRET), Instant.ofEpochSecond(161), "agent-auth", "session")).isInstanceOf(IllegalArgumentException.class);
    }
}
