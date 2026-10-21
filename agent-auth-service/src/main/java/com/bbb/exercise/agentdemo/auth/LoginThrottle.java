package com.bbb.exercise.agentdemo.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码登录防滥用：账户维度与来源维度的短期尝试预算。
 *
 * <p>历史问题是登录入口只有网关的路由 QPS，账户维度没有任何预算：攻击者可以在 QPS 额度内持续撞库，
 * 每次都触发一次 BCrypt cost 12 校验。这里给出一个跨实例共享、可自动解除的预算。
 *
 * <p>关键取舍：
 *
 * <ul>
 *   <li>用 <b>尝试</b> 计数而不是“失败”计数，成功即清零。这样“成功一次、再试错”的交替探测也会被
 *       计入，同时正常用户不会因为历史失败被长期惩罚。
 *   <li>键里只放归一化用户名与来源地址的 SHA-256，不落明文、更不落密码。
 *   <li>窗口用固定 TTL，<b>不会因为持续攻击而延长</b>，因此不存在把账户永久锁死的策略。
 *   <li>默认<b>不信任</b> {@code X-Forwarded-For}：只有直连地址命中 {@code trusted-proxies} 时才解析，
 *       且从右往左取第一个非可信地址，客户端伪造左侧条目无效。
 *   <li>Redis 不可用时降级为单实例内存预算，绝不因为缓存故障拒绝所有登录。
 * </ul>
 */
@Component
public class LoginThrottle {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /** 原子占用一次尝试额度：返回 0 表示放行，返回负数表示需要等待的秒数。 */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local n = redis.call('INCR', KEYS[1])
                    if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
                    if n > tonumber(ARGV[2]) then
                      local ttl = redis.call('TTL', KEYS[1])
                      if ttl == nil or ttl < 1 then ttl = tonumber(ARGV[1]) end
                      return -ttl
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int accountLimit;
    private final int sourceLimit;
    private final int windowSeconds;
    private final Set<String> trustedProxies;
    private final ConcurrentHashMap<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottle(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.security.login-throttle.enabled:true}") boolean enabled,
            @Value("${app.security.login-throttle.account-limit:10}") int accountLimit,
            @Value("${app.security.login-throttle.source-limit:30}") int sourceLimit,
            @Value("${app.security.login-throttle.window-seconds:900}") int windowSeconds,
            @Value("${app.security.login-throttle.trusted-proxies:}") String trustedProxies) {
        this(
                redis.getIfAvailable(),
                enabled,
                accountLimit,
                sourceLimit,
                windowSeconds,
                parseProxies(trustedProxies));
    }

    public LoginThrottle(
            StringRedisTemplate redis,
            boolean enabled,
            int accountLimit,
            int sourceLimit,
            int windowSeconds,
            Set<String> trustedProxies) {
        if (accountLimit < 1 || sourceLimit < 1) {
            throw new IllegalArgumentException("登录尝试预算必须为正数");
        }

        if (windowSeconds < 1 || windowSeconds > 86400) {
            throw new IllegalArgumentException("登录尝试窗口必须在 1..86400 秒之间");
        }

        this.redis = redis;
        this.enabled = enabled;
        this.accountLimit = accountLimit;
        this.sourceLimit = sourceLimit;
        this.windowSeconds = windowSeconds;
        this.trustedProxies = trustedProxies == null ? Set.of() : Set.copyOf(trustedProxies);
    }

    /**
     * 占用一次登录尝试额度。
     *
     * @return {@code null} 表示放行；否则为建议客户端等待的秒数
     */
    public Long acquire(String username, String source) {
        if (!enabled) {
            return null;
        }

        String accountKey = "auth:login:acct:" + digest(normalize(username));
        String sourceKey = "auth:login:src:" + digest(source == null ? "" : source);

        Long accountWait = consume(accountKey, accountLimit);
        Long sourceWait = consume(sourceKey, sourceLimit);

        // 两个维度都已被占用，取更长的等待时间，避免“换维度即绕过”。
        if (accountWait != null || sourceWait != null) {
            return Math.max(accountWait == null ? 0 : accountWait, sourceWait == null ? 0 : sourceWait);
        }

        return null;
    }

    /** 登录成功：清零账户维度的尝试预算，来源维度保留以持续压制同一来源的撞库。 */
    public void resetAccount(String username) {
        if (!enabled) {
            return;
        }

        String accountKey = "auth:login:acct:" + digest(normalize(username));

        if (redis != null) {
            try {
                redis.delete(accountKey);
                return;
            } catch (RuntimeException unavailable) {
                log.warn(
                        "login throttle redis unavailable on reset errorType={}",
                        unavailable.getClass().getSimpleName());
            }
        }

        localBuckets.remove(accountKey);
    }

    /**
     * 解析请求来源地址。
     *
     * <p>默认只使用 TCP 直连地址，不读 {@code X-Forwarded-For}。只有当直连地址是已配置的可信代理时才
     * 解析该头，并从右往左取第一个非可信地址，因此客户端无法通过伪造左侧条目改变自己的来源身份。
     */
    public String source(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }

        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String direct = remote == null || remote.getAddress() == null
                ? ""
                : remote.getAddress().getHostAddress();

        if (!trustedProxies.contains(direct)) {
            return direct;
        }

        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");

        if (forwarded == null || forwarded.isBlank()) {
            return direct;
        }

        String[] parts = forwarded.split(",");

        for (int index = parts.length - 1; index >= 0; index--) {
            String candidate = parts[index].trim();

            if (!candidate.isEmpty() && !trustedProxies.contains(candidate)) {
                return candidate;
            }
        }

        return direct;
    }

    /** 归一化用户名：与登录查询一致地去除首尾空白，并统一大小写以消除绕过。 */
    static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private Long consume(String key, int limit) {
        if (redis != null) {
            try {
                Long result =
                        redis.execute(
                                ACQUIRE_SCRIPT,
                                List.of(key),
                                String.valueOf(windowSeconds),
                                String.valueOf(limit));

                if (result == null || result >= 0) {
                    return null;
                }

                return -result;
            } catch (RuntimeException unavailable) {
                log.warn(
                        "login throttle redis unavailable, falling back to local budget errorType={}",
                        unavailable.getClass().getSimpleName());
            }
        }

        return consumeLocal(key, limit);
    }

    private Long consumeLocal(String key, int limit) {
        LocalBucket bucket = localBuckets.computeIfAbsent(key, ignored -> new LocalBucket());
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;

        synchronized (bucket) {
            if (bucket.windowStart == 0 || now - bucket.windowStart >= windowMillis) {
                bucket.windowStart = now;
                bucket.count = 0;
            }

            bucket.count++;

            if (bucket.count > limit) {
                return Math.max(1L, (bucket.windowStart + windowMillis - now) / 1000L);
            }
        }

        // 懒惰清理，避免内存桶随攻击者构造的用户名无限增长。
        if (localBuckets.size() > 10_000) {
            localBuckets.entrySet().removeIf(entry -> {
                LocalBucket candidate = entry.getValue();
                synchronized (candidate) {
                    return now - candidate.windowStart >= windowMillis;
                }
            });
        }

        return null;
    }

    private static Set<String> parseProxies(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 单实例降级预算：固定窗口，不在 Redis 故障时扩大拒绝面。 */
    private static final class LocalBucket {
        long windowStart;
        int count;
    }
}
