package com.bbb.exercise.agentdemo.common.client;
import com.bbb.exercise.agentdemo1_0.model.*;

import com.bbb.exercise.agentdemo.common.client.InternalServiceClient;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
        return new SelectedModel(ModelProvider.parse(remote.provider()), capability, remote.model(), remote.apiKey());
    }

    public List<ModelProfile> list(ChatIdentity identity) {
        throw new UnsupportedOperationException("模型配置必须通过 Auth 服务读取");
    }

    public ModelProfile save(ChatIdentity identity, SaveRequest request) {
        throw new UnsupportedOperationException("模型配置必须通过 Auth 服务写入");
    }

    public void clear(ChatIdentity identity, ModelCapability capability) {
        throw new UnsupportedOperationException("模型配置必须通过 Auth 服务删除");
    }

    public record SaveRequest(String provider, String capability, String model, String apiKey) {}
    public record SelectedModel(ModelProvider provider, ModelCapability capability, String model, String apiKey) {}
}

