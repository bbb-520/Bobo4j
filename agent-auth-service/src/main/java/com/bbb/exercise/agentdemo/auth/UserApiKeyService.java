package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.stereotype.Service;

/** Credentials come exclusively from server configuration. */
@Service
public class UserApiKeyService {
    private final AuthService auth;
    private final PlatformModels models;

    public UserApiKeyService(AuthService auth, PlatformModels models) {
        this.auth = auth;
        this.models = models;
    }

    // 1. 历史凭据写入口：平台统一管理供应商凭据，拒绝个人上传。
    public void save(ChatIdentity identity, String ignored) {
        auth.requireUserId(identity);
        throw new AuthService.AuthException(410, "平台统一提供模型凭据，无需上传 API Key");
    }

    // 2. 凭据状态：仅报告配置可用性，不向浏览器返回密钥正文。
    public KeyStatus status(ChatIdentity identity) {
        auth.requireUserId(identity);
        return new KeyStatus(models.configured(), null);
    }

    // 2.1. 内部凭据读取：供受信服务调用平台模型使用。
    public UserApiKeys get(ChatIdentity identity) {
        auth.requireUserId(identity);
        return new UserApiKeys(models.key());
    }

    public void clear(ChatIdentity identity) {
        auth.requireUserId(identity);
        throw new AuthService.AuthException(410, "平台凭据由管理员管理");
    }

    public void clearProvider(ChatIdentity identity, String provider) {
        clear(identity);
    }

    /** 凭据公开状态，仅返回是否配置及脱敏后的展示值。 */
    public record KeyStatus(boolean configured, String masked) {}

    /** 认证服务内部使用的供应商凭据值，避免在字符串输出中泄露密钥。 */
    public record UserApiKeys(String qwenApiKey) {
        public boolean hasQwen() {
            return qwenApiKey != null && !qwenApiKey.isBlank();
        }

        @Override
        public String toString() {
            return "PlatformApiKeys[REDACTED]";
        }
    }
}
