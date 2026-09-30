package com.bbb.exercise.agentdemo1_0.model;

import com.bbb.exercise.agentdemo1_0.auth.ApiKeyCrypto;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Persists per-user model selection without exposing key material to callers. */
@Service
@RequiredArgsConstructor
public class ModelProfileService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ApiKeyCrypto crypto;
    private final ModelProviderRegistry registry;

    public List<ModelProfile> list(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        return jdbc.query("SELECT provider,capability,model,enabled,api_key_ciphertext "
                        + "FROM user_model_profile WHERE user_id=? ORDER BY capability" +
                        "",
                (rs, rowNum) -> new ModelProfile(
                        ModelProvider.parse(rs.getString("provider")),
                        ModelCapability.parse(rs.getString("capability")),
                        rs.getString("model"),
                        rs.getBoolean("enabled"),
                        ModelProfile.maskSecret(rs.getString("api_key_ciphertext"))), userId);
    }

    public ModelProfile save(ChatIdentity identity, SaveRequest request) {
        long userId = auth.requireUserId(identity);
        if (request == null) throw new ResponseStatusException(BAD_REQUEST, "模型配置不能为空");
        ModelProvider provider = ModelProvider.parse(request.provider());
        ModelCapability capability = ModelCapability.parse(request.capability());
        registry.resolve(provider, capability, request.model());
        String apiKey = request.apiKey() == null ? null : request.apiKey().trim();
        if (apiKey == null || apiKey.isBlank()) {
            List<String> existing = jdbc.query("SELECT api_key_ciphertext FROM user_model_profile "
                            + "WHERE user_id=? AND capability=?",
                    (rs, rowNum) -> rs.getString(1), userId, capability.name());
            if (existing.isEmpty() || existing.get(0) == null || existing.get(0).isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "首次配置该模型时必须填写 API Key");
            }
            apiKey = existing.get(0);
        } else {
            apiKey = crypto.encrypt(apiKey);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO user_model_profile(user_id,provider,capability,model,api_key_ciphertext,enabled,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE provider=VALUES(provider),model=VALUES(model),"
                        + "api_key_ciphertext=VALUES(api_key_ciphertext),enabled=1,updated_at=VALUES(updated_at)",
                userId, provider.name(), capability.name(), request.model().trim(), apiKey, now, now);
        return list(identity).stream().filter(item -> item.capability() == capability).findFirst().orElseThrow();
    }

    public void clear(ChatIdentity identity, ModelCapability capability) {
        jdbc.update("DELETE FROM user_model_profile WHERE user_id=? AND capability=?",
                auth.requireUserId(identity), capability.name());
    }

    public SelectedModel resolve(ChatIdentity identity, ModelCapability capability) {
        long userId = auth.requireUserId(identity);
        List<SelectedModel> rows = jdbc.query("SELECT provider,capability,model,api_key_ciphertext,enabled "
                        + "FROM user_model_profile WHERE user_id=? AND capability=? AND enabled=1",
                (rs, rowNum) -> new SelectedModel(ModelProvider.parse(rs.getString("provider")),
                        ModelCapability.parse(rs.getString("capability")), rs.getString("model"),
                        crypto.decrypt(rs.getString("api_key_ciphertext"))), userId, capability.name());
        if (!rows.isEmpty()) return rows.get(0);
        // A single multimodal model profile can serve both chat and vision.
        if (capability == ModelCapability.VISION) {
            List<SelectedModel> chat = jdbc.query("SELECT provider,capability,model,api_key_ciphertext,enabled "
                            + "FROM user_model_profile WHERE user_id=? AND capability='CHAT' AND enabled=1",
                    (rs, rowNum) -> new SelectedModel(ModelProvider.parse(rs.getString("provider")),
                            ModelCapability.VISION, rs.getString("model"), crypto.decrypt(rs.getString("api_key_ciphertext"))), userId);
            if (!chat.isEmpty()) {
                SelectedModel candidate = chat.get(0);
                registry.resolve(candidate.provider(), ModelCapability.VISION, candidate.model());
                return candidate;
            }
        }
        return null;
    }

    public record SaveRequest(String provider, String capability, String model, String apiKey) {}

    public record SelectedModel(ModelProvider provider, ModelCapability capability, String model, String apiKey) {}
}
