package com.bbb.exercise.agentdemo1_0.auth;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCryptoTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomNonceAndRoundTrips() {
        var crypto = new ApiKeyCrypto(KEY);
        String first = crypto.encrypt("  secret-key  ");
        String second = crypto.encrypt("secret-key");
        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("secret-key");
    }

    @Test
    void rejectsMissingKeyAndLegacyPlaintext() {
        assertThatThrownBy(() -> new ApiKeyCrypto("").encrypt("secret"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyCrypto(KEY).decrypt("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未迁移");
    }
}
