package com.bbb.exercise.agentdemo1_0.auth;
import com.bbb.exercise.agentdemo.common.client.InternalServiceClient;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.stereotype.Service;
import java.time.Duration;
/** Read-only Auth facade. Credential writes belong exclusively to agent-auth-service. */
@Service public class UserApiKeyService {
 private final InternalServiceClient client; public UserApiKeyService(InternalServiceClient client){this.client=client;}
 public UserApiKeys get(ChatIdentity i){if(i==null||!i.authenticated())return new UserApiKeys(null);var v=client.credential(i.userId(),i.tenantId()).block(Duration.ofSeconds(5));return v==null?new UserApiKeys(null):new UserApiKeys(v.qwenApiKey());}
 public void save(ChatIdentity i,String k){throw new UnsupportedOperationException("API Key 必须通过 Auth 服务写入");} public KeyStatus status(ChatIdentity i){var k=get(i);return new KeyStatus(k.hasQwen(),k.hasQwen()?"••••••••":null);} public void clear(ChatIdentity i){throw new UnsupportedOperationException("API Key 必须通过 Auth 服务写入");} public void clearProvider(ChatIdentity i,String p){throw new UnsupportedOperationException("API Key 必须通过 Auth 服务写入");}
 public record KeyStatus(boolean configured,String masked){} public record UserApiKeys(String qwenApiKey){public boolean hasQwen(){return qwenApiKey!=null&&!qwenApiKey.isBlank();}}
}
