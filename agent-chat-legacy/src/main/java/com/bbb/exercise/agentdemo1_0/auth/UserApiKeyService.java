package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Stores the user's encrypted Alibaba DashScope/Qwen key. */
@Service
public class UserApiKeyService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ApiKeyCrypto crypto;

    public UserApiKeyService(JdbcTemplate jdbc, AuthService auth, ApiKeyCrypto crypto) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.crypto = crypto;
    }

    public void save(ChatIdentity identity, String qwen) {
        long userId = auth.requireUserId(identity);
        if (blank(qwen)) throw new IllegalArgumentException("请填写阿里云 API Key");
        jdbc.update("INSERT INTO user_api_key(user_id,qwen_api_key_ciphertext,updated_at) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE qwen_api_key_ciphertext=VALUES(qwen_api_key_ciphertext),updated_at=VALUES(updated_at)",
                userId, crypto.encrypt(qwen.trim()), LocalDateTime.now());
    }

    public KeyStatus status(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        var rows = jdbc.query("SELECT qwen_api_key_ciphertext FROM user_api_key WHERE user_id=?",
                (rs, n) -> rs.getString(1), userId);
        if (rows.isEmpty()) return new KeyStatus(false, null);
        return new KeyStatus(!blank(rows.get(0)), mask(rows.get(0)));
    }

    public UserApiKeys get(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        var rows = jdbc.query("SELECT qwen_api_key_ciphertext FROM user_api_key WHERE user_id=?",
                (rs, n) -> rs.getString(1), userId);
        if (rows.isEmpty() || blank(rows.get(0))) return new UserApiKeys(null);
        return new UserApiKeys(crypto.decrypt(rows.get(0)));
    }

    public void clear(ChatIdentity identity) {
        jdbc.update("DELETE FROM user_api_key WHERE user_id=?", auth.requireUserId(identity));
    }

    public void clearProvider(ChatIdentity identity, String provider) {
        if (!"qwen".equalsIgnoreCase(provider) && !"dashscope".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("不支持的 API Key 类型");
        }
        jdbc.update("UPDATE user_api_key SET qwen_api_key_ciphertext=NULL,updated_at=? WHERE user_id=?",
                LocalDateTime.now(), auth.requireUserId(identity));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String mask(String value) {
        if (blank(value)) return null;
        String v = value.trim();
        if (v.length() <= 8) return "••••••••";
        return v.substring(0, 4) + "••••••••" + v.substring(v.length() - 4);
    }

    public record KeyStatus(boolean configured, String masked) {}

    public record UserApiKeys(String qwenApiKey) {
        public boolean hasQwen() { return qwenApiKey != null && !qwenApiKey.isBlank(); }
    }
}
