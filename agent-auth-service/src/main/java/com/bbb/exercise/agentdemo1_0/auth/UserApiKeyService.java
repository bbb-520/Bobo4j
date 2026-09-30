package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.auth.entity.UserApiKeyEntity;
import com.bbb.exercise.agentdemo1_0.auth.mapper.UserApiKeyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Stores the user's encrypted Alibaba DashScope/Qwen key. */
@Service
public class UserApiKeyService {
    private final UserApiKeyMapper keys;
    private final AuthService auth;
    private final ApiKeyCrypto crypto;

    public UserApiKeyService(UserApiKeyMapper keys, AuthService auth, ApiKeyCrypto crypto) {
        this.keys = keys;
        this.auth = auth;
        this.crypto = crypto;
    }

    public void save(ChatIdentity identity, String qwen) {
        long userId = auth.requireUserId(identity);
        if (blank(qwen)) throw new IllegalArgumentException("请填写阿里云 API Key");
        UserApiKeyEntity entity = keys.selectOne(new LambdaQueryWrapper<UserApiKeyEntity>().eq(UserApiKeyEntity::getUserId, userId));
        if (entity == null) { entity = new UserApiKeyEntity(); entity.setUserId(userId); }
        entity.setQwenApiKeyCiphertext(crypto.encrypt(qwen.trim())); entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) keys.insert(entity); else keys.updateById(entity);
    }

    public KeyStatus status(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        UserApiKeyEntity entity = keys.selectOne(new LambdaQueryWrapper<UserApiKeyEntity>().eq(UserApiKeyEntity::getUserId, userId));
        if (entity == null) return new KeyStatus(false, null);
        return new KeyStatus(!blank(entity.getQwenApiKeyCiphertext()), mask(entity.getQwenApiKeyCiphertext()));
    }

    public UserApiKeys get(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        UserApiKeyEntity entity = keys.selectOne(new LambdaQueryWrapper<UserApiKeyEntity>().eq(UserApiKeyEntity::getUserId, userId));
        if (entity == null || blank(entity.getQwenApiKeyCiphertext())) return new UserApiKeys(null);
        return new UserApiKeys(crypto.decrypt(entity.getQwenApiKeyCiphertext()));
    }

    public void clear(ChatIdentity identity) {
        keys.delete(new LambdaQueryWrapper<UserApiKeyEntity>().eq(UserApiKeyEntity::getUserId, auth.requireUserId(identity)));
    }

    public void clearProvider(ChatIdentity identity, String provider) {
        if (!"qwen".equalsIgnoreCase(provider) && !"dashscope".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("不支持的 API Key 类型");
        }
        UserApiKeyEntity entity = keys.selectOne(new LambdaQueryWrapper<UserApiKeyEntity>().eq(UserApiKeyEntity::getUserId, auth.requireUserId(identity)));
        if (entity != null) { entity.setQwenApiKeyCiphertext(null); entity.setUpdatedAt(LocalDateTime.now()); keys.updateById(entity); }
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
