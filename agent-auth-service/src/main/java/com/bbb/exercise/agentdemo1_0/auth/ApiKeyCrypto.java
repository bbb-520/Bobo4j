package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Versioned AES-256-GCM envelope for user credentials. Plaintext is never
 * accepted as a silent fallback; legacy rows must be migrated explicitly.
 */
@Component
public class ApiKeyCrypto {
    private static final String PREFIX = "v1:";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCrypto(@Value("${app.security.api-key-encryption-key:}") String encodedKey) {
        try {
            this.key = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("app.security.api-key-encryption-key 必须是 Base64", error);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        requireKey();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (Exception error) {
            throw new IllegalStateException("API Key 加密失败", error);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        requireKey();
        if (!value.startsWith(PREFIX)) throw new IllegalStateException("检测到未迁移的 API Key，请先执行密文迁移");
        try {
            byte[] envelope = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (envelope.length <= 12) throw new IllegalArgumentException("密文长度无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, java.util.Arrays.copyOf(envelope, 12)));
            return new String(cipher.doFinal(java.util.Arrays.copyOfRange(envelope, 12, envelope.length)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("API Key 解密失败", error);
        }
    }

    private void requireKey() {
        if (key.length != 32) throw new IllegalStateException("必须配置 32 字节 Base64 API Key 加密密钥");
    }
}
