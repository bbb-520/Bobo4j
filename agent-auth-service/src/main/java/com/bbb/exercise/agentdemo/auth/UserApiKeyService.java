package com.bbb.exercise.agentdemo.auth;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.stereotype.Service;
/** Credentials come exclusively from server configuration. */
@Service
public class UserApiKeyService {
    private final AuthService auth; private final PlatformModels models;
    public UserApiKeyService(AuthService auth,PlatformModels models) {this.auth=auth;this.models=models;}
    public void save(ChatIdentity identity,String ignored) {auth.requireUserId(identity);throw new AuthService.AuthException(410,"平台统一提供模型凭据，无需上传 API Key");}
    public KeyStatus status(ChatIdentity identity) {auth.requireUserId(identity);return new KeyStatus(models.configured(),null);}
    public UserApiKeys get(ChatIdentity identity) {auth.requireUserId(identity);return new UserApiKeys(models.key());}
    public void clear(ChatIdentity identity) {auth.requireUserId(identity);throw new AuthService.AuthException(410,"平台凭据由管理员管理");}
    public void clearProvider(ChatIdentity identity,String provider) {clear(identity);}
    public record KeyStatus(boolean configured,String masked) {}
    public record UserApiKeys(String qwenApiKey) {
        public boolean hasQwen() {return qwenApiKey!=null&&!qwenApiKey.isBlank();}
        @Override public String toString() {return "PlatformApiKeys[REDACTED]";}
    }
}
