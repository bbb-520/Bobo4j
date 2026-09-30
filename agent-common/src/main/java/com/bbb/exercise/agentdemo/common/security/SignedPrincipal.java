package com.bbb.exercise.agentdemo.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small dependency-free HMAC principal contract for transitional service calls. */
public final class SignedPrincipal {
    private static final String HMAC = "HmacSHA256";
    private SignedPrincipal() {}

    public static String issue(String service, String subject, String tenant, Instant expiresAt, byte[] secret) {
        return issue("default", service, subject, tenant, expiresAt, secret);
    }

    public static String issue(String keyId, String service, String subject, String tenant, Instant expiresAt, byte[] secret) {
        if (blank(keyId)) throw new IllegalArgumentException("签名 principal keyId 无效");
        if (blank(service) || blank(subject) || blank(tenant) || expiresAt == null || secret == null || secret.length < 32)
            throw new IllegalArgumentException("签名 principal 参数无效");
        String payload = keyId + "|" + service + "|" + subject + "|" + tenant + "|" + expiresAt.getEpochSecond();
        return encode(payload) + "." + encode(sign(payload, secret));
    }

    public static Principal verify(String token, byte[] secret, Instant now) {
        return verify(token, Map.of("default", secret), now);
    }

    public static Principal verify(String token, Map<String, byte[]> secrets, Instant now) {
        if (secrets == null || secrets.isEmpty()) throw new IllegalArgumentException("签名 principal 密钥未配置");
        if (blank(token)) throw new IllegalArgumentException("签名 principal 配置无效");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("签名 principal 格式无效");
        String payload = decode(parts[0]);
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 || blank(fields[0])) throw new IllegalArgumentException("签名 principal 内容无效");
        byte[] secret = secrets.get(fields[0]);
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("签名 principal keyId 未知");
        byte[] expected = sign(payload, secret);
        if (!MessageDigest.isEqual(expected, decodeBytes(parts[1]))) throw new IllegalArgumentException("签名 principal 校验失败");
        if (blank(fields[1]) || blank(fields[2]) || blank(fields[3])) throw new IllegalArgumentException("签名 principal 内容无效");
        long expiry; try { expiry = Long.parseLong(fields[4]); } catch (NumberFormatException e) { throw new IllegalArgumentException("签名 principal 过期时间无效", e); }
        if (now == null || expiry <= now.getEpochSecond()) throw new IllegalArgumentException("签名 principal 已过期");
        return new Principal(fields[0], fields[1], fields[2], fields[3], Instant.ofEpochSecond(expiry));
    }

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
