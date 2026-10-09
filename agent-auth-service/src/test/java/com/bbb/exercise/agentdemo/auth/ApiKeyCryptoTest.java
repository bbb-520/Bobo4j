package com.bbb.exercise.agentdemo.auth;

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
        assertThat(first).startsWith("v2:current:").isNotEqualTo(second);
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

    @Test
    void readsPreviousKeyButAlwaysWritesWithActiveKey() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
        String activeKey = Base64.getEncoder().encodeToString(new byte[32].clone());
        activeKey = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        var oldCrypto = new ApiKeyCrypto("old=" + oldKey, "old");
        var rotating = new ApiKeyCrypto("old=" + oldKey + ",current=" + activeKey, "current");
        String oldCiphertext = oldCrypto.encrypt("legacy-secret");
        assertThat(rotating.decrypt(oldCiphertext)).isEqualTo("legacy-secret");
        assertThat(rotating.encrypt("new-secret")).startsWith("v2:current:");
    }
}
