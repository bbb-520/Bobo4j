package com.bbb.exercise.agentdemo.auth.model;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.PlatformModels;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** 模型配置应用服务，提供配置视图并依据平台托管策略校验和解析模型选择。 */
@Service
public class ModelProfileService {
    private final AuthService auth;
    private final PlatformModels models;

    public ModelProfileService(AuthService auth, PlatformModels models) {
        this.auth = auth;
        this.models = models;
    }

    // 1. 模型查询：对外展示平台按能力配置的模型。
    public List<ModelProfile> list(ChatIdentity identity) {
        auth.requireUserId(identity);
        return List.of(ModelCapability.values()).stream()
                .map(
                        c -> {
                            try {
                                var m = models.select(c);
                                return new ModelProfile(m.provider(), c, m.model(), true, null);
                            } catch (AuthService.AuthException unavailable) {
                                return new ModelProfile(null, c, "", false, null);
                            }
                        })
                .toList();
    }

    // 2. 历史写入口：个人模型配置写入已退役，明确返回业务错误。
    public ModelProfile save(ChatIdentity identity, SaveRequest ignored) {
        auth.requireUserId(identity);
        throw new AuthService.AuthException(410, "平台模型由管理员统一配置");
    }

    public void clear(ChatIdentity identity, ModelCapability ignored) {
        auth.requireUserId(identity);
        throw new AuthService.AuthException(410, "平台模型由管理员统一配置");
    }

    // 3. 模型解析：服务内部按所需能力取得平台模型配置。
    public SelectedModel resolve(ChatIdentity identity, ModelCapability c) {
        auth.requireUserId(identity);
        return models.select(c);
    }

    /** 模型配置提交参数，携带供应商、能力、模型名及密钥。 */
    public record SaveRequest(String provider, String capability, String model, String apiKey) {}

    /** 服务内部使用的模型路由，关联实际模型、端点及受保护的凭据标识。 */
    public record SelectedModel(
            ModelProvider provider,
            ModelCapability capability,
            String model,
            String apiKey,
            String baseUrl,
            String credentialId) {
        public SelectedModel(
                ModelProvider provider, ModelCapability capability, String model, String apiKey) {
            this(provider, capability, model, apiKey, null, "platform");
        }

        @Override
        public String toString() {
            return "SelectedModel[provider="
                    + provider
                    + ",capability="
                    + capability
                    + ",model="
                    + model
                    + "]";
        }
    }
}
