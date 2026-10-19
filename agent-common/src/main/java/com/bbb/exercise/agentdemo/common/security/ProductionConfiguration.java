package com.bbb.exercise.agentdemo.common.security;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Validates resolved production settings before any service context or database pool starts. */
public final class ProductionConfiguration implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) return;

        ring(value(environment, "app.security.internal-principal-secrets", "INTERNAL_PRINCIPAL_SECRETS"),
                value(environment, "app.security.internal-principal-active-key-id", "INTERNAL_PRINCIPAL_ACTIVE_KEY_ID"),
                "app.security.internal-principal-secrets", "app.security.internal-principal-active-key-id");
        String service = environment.getProperty("spring.application.name", "");
        if (service.equals("agent-auth-service")) {
            String otp = value(environment, "app.email.otp-secret", "AUTH_OTP_SECRET");
            if (otp == null || otp.length() < 32 || placeholder(otp)) fail("app.email.otp-secret");
        }

        if (environment.getProperty("spring.datasource.url", "").startsWith("jdbc:")) {
            password(environment, "spring.datasource.password", "MYSQL_PASSWORD");
        }
        if (environment.containsProperty("spring.data.redis.host")) {
            password(environment, "spring.data.redis.password", "REDIS_PASSWORD");
        }
        for (String type : new String[] {"discovery", "config"}) {
            String prefix = "spring.cloud.nacos." + type;
            if (environment.containsProperty(prefix + ".server-addr")
                    && environment.getProperty(prefix + ".enabled", Boolean.class, true)) {
                password(environment, prefix + ".password", "NACOS_PASSWORD");
            }
        }
        if (service.equals("agent-gateway")) {
            origins(value(environment, "app.cors.allowed-origins", "CORS_ALLOWED_ORIGINS"));
        }
        if (environment.getProperty("app.payments.alipay.enabled", Boolean.class, false)) {
            String notify = value(environment, "app.payments.notify-base-url", "PAYMENT_NOTIFY_BASE_URL");
            if (!https(notify, false)) fail("app.payments.notify-base-url");
        }
    }

    private static String value(ConfigurableEnvironment environment, String property, String variable) {
        String value = environment.getProperty(property);
        return value == null ? environment.getProperty(variable) : value;
    }

    private static void password(ConfigurableEnvironment environment, String property, String variable) {
        String password = value(environment, property, variable);
        if (password == null || password.isBlank() || placeholder(password)) fail(property);
    }

    private static boolean placeholder(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("nacos", "password", "changeme", "change-me", "change-root-me", "change-redis-me")
                .contains(normalized) || normalized.startsWith("replace-")
                || normalized.startsWith("replace_") || normalized.startsWith("set-")
                || normalized.startsWith("your-");
    }

    private static void ring(String encoded, String active, String property, String activeProperty) {
        if (encoded == null || encoded.isBlank()) fail(property);
        var ids = new HashSet<String>();
        for (String item : encoded.split(",", -1)) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,64}") || !ids.add(pair[0])) fail(property);
            key(pair[1].trim(), property);
        }
        if (active == null || !ids.contains(active)) fail(activeProperty);
    }

    private static void key(String encoded, String property) {
        if (encoded == null || encoded.isBlank()) fail(property);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Unsafe or missing production setting: " + property);
        }
        if (decoded.length < 32) fail(property);
        byte[] published = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        if (Arrays.equals(decoded, published)) fail(property);
        boolean identical = true;
        for (int i = 1; i < decoded.length; i++) identical &= decoded[i] == decoded[0];
        if (identical) fail(property);
    }

    private static void origins(String origins) {
        if (origins == null || origins.isBlank()) fail("app.cors.allowed-origins");
        for (String origin : origins.split(",", -1)) {
            if (!https(origin.trim(), true)) fail("app.cors.allowed-origins");
        }
    }

    private static boolean https(String text, boolean originOnly) {
        if (text == null || text.isBlank() || text.contains("*")) return false;
        try {
            URI uri = URI.create(text);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (!originOnly || uri.getPath() == null || uri.getPath().isEmpty());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void fail(String property) {
        throw new IllegalStateException("Unsafe or missing production setting: " + property);
    }
}
