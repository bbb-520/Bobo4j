package com.bbb.exercise.agentdemo.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small dependency-free HMAC principal contract for scoped internal service calls. */
public final class SignedPrincipal {
    private static final String HMAC = "HmacSHA256";
    private SignedPrincipal() {}

    private static byte[] sign(String payload, byte[] secret) {
        try { Mac mac = Mac.getInstance(HMAC); mac.init(new SecretKeySpec(secret, HMAC)); return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("签名 principal 失败", e); }
    }
    private static String encode(String value) { return encode(value.getBytes(StandardCharsets.UTF_8)); }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String decode(String value) { return new String(decodeBytes(value), StandardCharsets.UTF_8); }
    private static byte[] decodeBytes(String value) { try { return Base64.getUrlDecoder().decode(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("签名 principal Base64 无效", e); } }
    private static boolean blank(String value) { return value == null || value.isBlank() || value.indexOf('|') >= 0; }

    public record Principal(String keyId, String service, String subject, String tenant, Instant expiresAt) {}

    public static String issueScoped(String keyId, String service, String subject, String tenant,
                                      String audience, String operation, Instant now, byte[] secret) {
        if (blank(keyId) || blank(service) || blank(subject) || blank(tenant) || blank(audience)
                || blank(operation) || now == null || secret == null || secret.length < 32)
            throw new IllegalArgumentException("内部 principal 参数无效");
        String payload = String.join("|", "v2", keyId, service, subject, tenant, audience, operation,
                Long.toString(now.getEpochSecond()), Long.toString(now.plusSeconds(60).getEpochSecond()));
        return encode(payload) + "." + encode(sign(payload, secret));
    }

    public static Scoped verifyScoped(String token, Map<String, byte[]> secrets, Instant now,
                                      String audience, String operation) {
        if (token == null || token.length() > 4096 || secrets == null || now == null)
            throw new IllegalArgumentException("内部 principal 无效");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("内部 principal 格式无效");
        String payload = decode(parts[0]);
        String[] f = payload.split("\\|", -1);
        if (f.length != 9 || !"v2".equals(f[0])) throw new IllegalArgumentException("必须使用 v2 内部 principal");
        for (String field : f) if (blank(field)) throw new IllegalArgumentException("内部 principal 字段无效");
        byte[] key = secrets.get(f[1]);
        if (key == null || key.length < 32 || !MessageDigest.isEqual(sign(payload, key), decodeBytes(parts[1])))
            throw new IllegalArgumentException("内部 principal 签名无效");
        long issued = Long.parseLong(f[7]);
        long expiry = Long.parseLong(f[8]);
        if (issued > now.getEpochSecond() + 5 || expiry <= now.getEpochSecond()
                || expiry <= issued || expiry - issued > 60 || !f[5].equals(audience) || !f[6].equals(operation))
            throw new IllegalArgumentException("内部 principal 已过期、受众或操作不匹配");
        return new Scoped(f[1], f[2], f[3], f[4], f[5]);
    }

    public record Scoped(String keyId, String service, String subject, String tenant, String audience) {}
}
