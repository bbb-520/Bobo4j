package com.bbb.exercise.agentdemo.runtime.client;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Read-only model selection facade. Auth is the sole owner of model-profile persistence. */
@Service
public class AuthModelClient {
    private final InternalServiceClient authClient;

    public AuthModelClient(InternalServiceClient authClient) {
        this.authClient = authClient;
    }

    public SelectedModel resolve(ChatIdentity identity, ModelCapability capability) {
        var remote = authClient.model(identity.userId(), identity.tenantId(), capability.name())
                .block(Duration.ofSeconds(5));
        if (remote == null) return null;
        return new SelectedModel(ModelProvider.parse(remote.provider()), capability, remote.model(), remote.apiKey(),remote.baseUrl(),remote.credentialId());
    }

    public record SelectedModel(ModelProvider provider, ModelCapability capability, String model, String apiKey,String baseUrl,String credentialId) {
        public SelectedModel(ModelProvider provider,ModelCapability capability,String model,String apiKey) {this(provider,capability,model,apiKey,null,"platform");}
        @Override
        public String toString() {
            return "SelectedModel[provider=" + provider + ", capability=" + capability
                    + ", model=" + model + ", apiKey=[REDACTED]]";
        }
    }
}
