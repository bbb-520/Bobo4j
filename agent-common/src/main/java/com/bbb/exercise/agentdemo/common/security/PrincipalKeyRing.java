package com.bbb.exercise.agentdemo.common.security;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit active signing key, with previous keys retained only for verification. */
public final class PrincipalKeyRing {
    private final Map<String, byte[]> keys;
    private final String active;

    public PrincipalKeyRing(String encodedKeys, String active) {
        var parsed = new LinkedHashMap<String, byte[]>();
        if (encodedKeys != null && !encodedKeys.isBlank()) {
            for (String item : encodedKeys.split(",")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,64}"))
                    throw new IllegalArgumentException("内部密钥格式应为 keyId=Base64");
                byte[] key = Base64.getDecoder().decode(pair[1]);
                if (key.length < 32 || parsed.putIfAbsent(pair[0], key) != null)
                    throw new IllegalArgumentException("内部密钥长度不足或 keyId 重复");
            }
        }
        this.keys = Map.copyOf(parsed);
        this.active = active;
    }

    public String sign(String service, String subject, String tenant, String audience, String operation) {
        byte[] key = keys.get(active);
        if (key == null) throw new IllegalStateException("必须配置内部 active-key-id 和对应密钥");
        return SignedPrincipal.issueScoped(active, service, subject, tenant, audience, operation,
                java.time.Instant.now(), key);
    }

    public SignedPrincipal.Scoped verify(String token, String audience, String operation) {
        return SignedPrincipal.verifyScoped(token, keys, java.time.Instant.now(), audience, operation);
    }
}
