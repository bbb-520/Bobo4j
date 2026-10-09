package com.bbb.exercise.agentdemo.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned AES-256-GCM envelope with read-many/write-active key rotation. */
@Component
public class ApiKeyCrypto {
    private static final String V2 = "v2:";
    private static final String V1 = "v1:";
    private final Map<String, byte[]> keys;
    private final String activeKeyId;
    private final byte[] legacyV1Key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ApiKeyCrypto(
            @Value("${app.security.api-key-encryption-keys:}") String encodedKeys,
            @Value("${app.security.api-key-active-key-id:current}") String activeKeyId,
            @Value("${app.security.api-key-encryption-key:}") String legacyKey) {
        this(buildKeys(encodedKeys, legacyKey), activeKeyId, decodeOptional(legacyKey));
    }

    /** Test/compatibility constructor for the former single-key configuration. */
    public ApiKeyCrypto(String encodedKey) {
        this("current=" + (encodedKey == null ? "" : encodedKey), "current");
    }

    public ApiKeyCrypto(String encodedKeys, String activeKeyId) {
        this(parseKeys(encodedKeys), activeKeyId, null);
    }

    private ApiKeyCrypto(Map<String, byte[]> keys, String activeKeyId, byte[] legacyV1Key) {
        this.keys = Map.copyOf(keys);
        this.activeKeyId = activeKeyId;
        this.legacyV1Key = legacyV1Key;
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        byte[] key = key(activeKeyId);
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return V2 + activeKeyId + ":" + Base64.getEncoder().encodeToString(envelope);
        } catch (Exception error) {
            throw new IllegalStateException("API Key 加密失败", error);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith(V2)) {
            int separator = value.indexOf(':', V2.length());
            if (separator < 0) throw new IllegalStateException("API Key v2 密文格式无效");
            String keyId = value.substring(V2.length(), separator);
            return decryptEnvelope(value.substring(separator + 1), key(keyId));
        }
        if (value.startsWith(V1)) {
            return decryptEnvelope(value.substring(V1.length()), legacyV1Key == null ? key(activeKeyId) : legacyV1Key);
        }
        throw new IllegalStateException("检测到未迁移的 API Key，请先执行密文迁移");
    }

    private String decryptEnvelope(String encodedEnvelope, byte[] key) {
        try {
            byte[] envelope = Base64.getDecoder().decode(encodedEnvelope);
            if (envelope.length <= 12) throw new IllegalArgumentException("密文长度无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, java.util.Arrays.copyOf(envelope, 12)));
            return new String(cipher.doFinal(java.util.Arrays.copyOfRange(envelope, 12, envelope.length)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("API Key 解密失败", error);
        }
    }

    private byte[] key(String keyId) {
        byte[] key = keys.get(keyId);
        if (key == null || key.length != 32) throw new IllegalStateException("必须配置 32 字节 Base64 API Key 加密密钥");
        return key;
    }

    private static Map<String, byte[]> buildKeys(String encodedKeys, String legacyKey) {
        String source = encodedKeys == null || encodedKeys.isBlank()
                ? "current=" + (legacyKey == null ? "" : legacyKey) : encodedKeys;
        return parseKeys(source);
    }

    private static byte[] decodeOptional(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IllegalStateException("必须配置 32 字节 Base64 API Key 加密密钥");
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("API Key 密钥必须是 Base64", error);
        }
    }

    private static Map<String, byte[]> parseKeys(String encodedKeys) {
        var parsed = new LinkedHashMap<String, byte[]>();
        if (encodedKeys != null && !encodedKeys.isBlank()) {
            for (String item : encodedKeys.split(",")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,64}"))
                    throw new IllegalStateException("API Key 密钥必须是 keyId=Base64[,keyId=Base64]");
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(pair[1].trim()); }
                catch (IllegalArgumentException error) { throw new IllegalStateException("API Key 密钥必须是 Base64", error); }
                if (decoded.length != 32 || parsed.putIfAbsent(pair[0], decoded) != null)
                    throw new IllegalStateException("API Key 密钥必须是唯一的 32 字节 key");
            }
        }
        return parsed;
    }
}
