package com.bbb.exercise.agentdemo.common.client;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Read-only client for Auth-owned credentials; writes never cross service boundaries. */
@Component
public class AuthCredentialClient {
    private final InternalServiceClient client;
    public AuthCredentialClient(InternalServiceClient client) { this.client = client; }
    public UserApiKeys get(ChatIdentity identity) {
        if (identity == null || !identity.authenticated()) return new UserApiKeys(null);
        var value = client.credential(identity.userId(), identity.tenantId()).block(Duration.ofSeconds(5));
        return value == null ? new UserApiKeys(null) : new UserApiKeys(value.qwenApiKey());
    }
    public record UserApiKeys(String qwenApiKey) {
        public boolean hasQwen() { return qwenApiKey != null && !qwenApiKey.isBlank(); }
    }
}
