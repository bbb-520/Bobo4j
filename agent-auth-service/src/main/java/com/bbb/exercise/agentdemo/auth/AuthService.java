package com.bbb.exercise.agentdemo.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bbb.exercise.agentdemo.auth.entity.*;
import com.bbb.exercise.agentdemo.auth.mapper.*;
import com.bbb.exercise.agentdemo.auth.rbac.RbacService;
import com.bbb.exercise.agentdemo.runtime.config.AppProperties;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ServerWebExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 账户与会话核心服务，管理密码登录、会话哈希、有效身份及公开用户标识。 */
@Service
public class AuthService {
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private final AppUserMapper users;
    private final AuthSessionMapper sessions;
    private final PasswordHasher passwords;
    private final AppProperties properties;
    private final RbacService rbac;
    private final LoginThrottle loginThrottle;

    @Autowired
    public AuthService(
            AppUserMapper users,
            AuthSessionMapper sessions,
            PasswordHasher passwords,
            AppProperties properties,
            RbacService rbac,
            LoginThrottle loginThrottle) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.properties = properties;
        this.rbac = rbac;
        this.loginThrottle = loginThrottle;
    }

    /** Identity-only embedding constructor; Spring always injects the full authorization service. */
    public AuthService(
            AppUserMapper users,
            AuthSessionMapper sessions,
            PasswordHasher passwords,
            AppProperties properties) {
        this(users, sessions, passwords, properties, null, null);
    }

    public AuthService(
            AppUserMapper users,
            AuthSessionMapper sessions,
            PasswordHasher passwords,
            AppProperties properties,
            RbacService rbac) {
        this(users, sessions, passwords, properties, rbac, null);
    }

    // 1. 账户持久化：校验注册字段，保存密码哈希并生成稳定的公开用户 ID。
    public User register(String username, String password) {
        validate(username, password);

        try {
            var e = new AppUserEntity();

            e.setUsername(username.trim());
            e.setPasswordHash(passwords.hash(password));
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(e.getCreatedAt());
            e.setStatus("ACTIVE");
            users.insert(e);
            e.setPublicUserId(publicUserId(new User(e.getId(), e.getUsername(), "")));
            users.updateById(e);

            return new User(e.getId(), e.getUsername(), e.getPasswordHash());
        } catch (DuplicateKeyException e) {
            throw new AuthException(409, "用户名已存在");
        }
    }

    // 2. 密码登录：验证账户凭据后，统一走会话签发流程。
    public LoginResult login(String username, String password) {
        return login(username, password, null);
    }

    /**
     * 密码登录，并带上调用来源以便按账户与来源两个维度限制撞库。
     *
     * <p>预算在触达数据库和 BCrypt 之前占用：QPS 额度内的持续撞库既不会放大数据库压力，也不会反复
     * 触发昂贵的密码哈希校验。校验成功后清零账户维度预算。
     */
    @Transactional
    public LoginResult login(String username, String password, String source) {
        validate(username, password);

        Long retryAfter =
                loginThrottle == null ? null : loginThrottle.acquire(username, source);

        if (retryAfter != null) {
            // 账户是否存在都走到这里，响应不区分未知账户。
            throw new AuthException(
                    429, "登录尝试过于频繁，请在 " + Math.max(1L, retryAfter) + " 秒后重试");
        }

        var e =
                users.selectOne(
                        new LambdaQueryWrapper<AppUserEntity>()
                                .eq(AppUserEntity::getUsername, username.trim()));

        if (e == null || !passwords.matches(password, e.getPasswordHash())) {
            throw new AuthException(401, "用户名或密码错误");
        }

        if (loginThrottle != null) {
            loginThrottle.resetAccount(username);
        }

        return issueSession(new User(e.getId(), e.getUsername(), e.getPasswordHash()));
    }

    // 2.1. 会话签发：与禁用账户共用用户行锁，防止并发重新创建已撤销的会话。
    @Transactional
    public LoginResult issueSession(User user) {
        // Shares a row lock with disable, so a concurrent login cannot recreate a revoked session.
        var current = users.lockById(user.id());

        if (!active(current)) {
            throw new AuthException(401, "账户不可用，请联系管理员");
        }

        if (rbac != null) {
            rbac.snapshot(user.id());
        }

        // 2.1.1. 原始令牌仅返回调用方；数据库保存哈希和有效期。
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        var s = new AuthSessionEntity();

        s.setUserId(user.id());
        s.setTokenHash(hash(token));
        s.setExpiresAt(LocalDateTime.now().plus(SESSION_TTL));
        s.setCreatedAt(LocalDateTime.now());
        sessions.insert(s);
        current.setLastLoginAt(LocalDateTime.now());
        users.updateById(current);

        return new LoginResult(user, token);
    }

    // 3. 浏览器身份解析：由 Cookie 查询有效会话，并再次确认账户可用。
    public ChatIdentity resolve(ServerWebExchange exchange) {
        var c = exchange.getRequest().getCookies().getFirst(sessionCookieName());
        var s = c == null ? null : session(c.getValue());

        return s == null || !active(users.selectById(s.getUserId()))
                ? null
                : new ChatIdentity(tenant(), "user:" + s.getUserId(), true);
    }

    // 3.1. 会话查询：对原始令牌做哈希匹配，并排除已过期记录。
    private AuthSessionEntity session(String token) {
        if (token == null || token.isBlank() || token.length() > 512) {
            return null;
        }

        return sessions.selectOne(
                new LambdaQueryWrapper<AuthSessionEntity>()
                        .eq(AuthSessionEntity::getTokenHash, hash(token))
                        .gt(AuthSessionEntity::getExpiresAt, LocalDateTime.now()));
    }

    // 3.2. 内部身份查询：将会话令牌转换为包含实时角色权限的公开身份。
    public UserIdentity resolveSessionToken(String token) {
        var s = session(token);

        if (s == null) {
            return null;
        }

        var u = users.selectById(s.getUserId());

        return active(u) ? identity(u) : null;
    }

    private UserIdentity identity(AppUserEntity u) {
        var snapshot =
                rbac == null
                        ? new RbacService.Snapshot(Set.of(), Set.of())
                        : rbac.snapshot(u.getId());
        return new UserIdentity(
                publicUserId(new User(u.getId(), u.getUsername(), "")),
                tenant(),
                true,
                u.getUsername(),
                snapshot.roles(),
                snapshot.permissions());
    }

    private static boolean active(AppUserEntity u) {
        return u != null && "ACTIVE".equals(u.getStatus());
    }

    // 4. 注销：删除当前令牌对应的会话，不撤销其他设备的会话。
    public void logout(ServerWebExchange ex) {
        var c = ex.getRequest().getCookies().getFirst(sessionCookieName());

        if (c != null) {
            sessions.delete(
                    new LambdaQueryWrapper<AuthSessionEntity>()
                            .eq(AuthSessionEntity::getTokenHash, hash(c.getValue())));
        }
    }

    public String sessionHash(ServerWebExchange ex) {
        var c = ex.getRequest().getCookies().getFirst(sessionCookieName());

        if (c == null) {
            throw new AuthException(401, "请先登录");
        }

        return hash(c.getValue());
    }

    public String sessionCookieName() {
        return properties.getSecurity().getSessionCookieName();
    }

    public Duration sessionTtl() {
        return SESSION_TTL;
    }

    private String tenant() {
        return properties.getSecurity().getDefaultTenantId();
    }

    // 5. 本地账户标识：仅接受认证后的 user:数字 格式，避免混用公开 UUID。
    public long requireUserId(ChatIdentity i) {
        if (i == null || !i.authenticated() || !i.userId().matches("user:[0-9]+")) {
            throw new AuthException(401, "请先登录");
        }

        return Long.parseLong(i.userId().substring(5));
    }

    public String username(ChatIdentity i) {
        var u = users.selectById(requireUserId(i));
        return u == null ? null : u.getUsername();
    }

    // 5.1. 公开标识转换：通过固定命名空间派生 UUID，保证跨服务标识稳定。
    public String publicUserId(User u) {
        return UUID.nameUUIDFromBytes(("bobo:user:" + u.id()).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public String publicUserId(ChatIdentity i) {
        return publicUserId(new User(requireUserId(i), "", ""));
    }

    public UserIdentity resolvePublicUser(String id, String tenant) {
        var u = findPublicUser(id, tenant);
        return u == null ? null : identity(u);
    }

    public ChatIdentity identityForPublicUser(String id, String tenant) {
        var u = findPublicUser(id, tenant);
        return u == null ? null : new ChatIdentity(tenant, "user:" + u.getId(), true);
    }

    // 5.2. 跨服务账户定位：同时约束公开 ID、租户和账户状态。
    private AppUserEntity findPublicUser(String id, String tenant) {
        if (id == null || !id.matches("[0-9a-f-]{36}") || !tenant().equals(tenant)) {
            return null;
        }

        var u =
                users.selectOne(
                        new LambdaQueryWrapper<AppUserEntity>()
                                .eq(AppUserEntity::getPublicUserId, id));

        return active(u) ? u : null;
    }

    private static void validate(String u, String p) {
        if (u == null || !u.matches("[A-Za-z0-9_.@-]{3,64}")) {
            throw new AuthException(400, "用户名需为 3-64 位字母、数字或 _ . @ -");
        }

        if (p == null || p.length() < 8 || p.length() > 128) {
            throw new AuthException(400, "密码长度需为 8-128 位");
        }
    }

    private static String hash(String t) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(t.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 认证内部使用的账户快照，保存数据库主键、用户名和密码哈希。 */
    public record User(long id, String username, String passwordHash) {
        @Override
        public String toString() {
            return "User[id=" + id + ",username=" + username + "]";
        }
    }

    /** 登录成功结果，将账户信息与新签发的会话令牌关联。 */
    public record LoginResult(User user, String token) {
        @Override
        public String toString() {
            return "LoginResult[REDACTED]";
        }
    }

    /** 当前账户的认证视图，携带公开用户标识、租户和有效角色权限。 */
    public record UserIdentity(
            String userId,
            String tenantId,
            boolean authenticated,
            String username,
            Set<String> roles,
            Set<String> permissions) {
        public UserIdentity(String id, String tenant, boolean authenticated, String username) {
            this(id, tenant, authenticated, username, Set.of(), Set.of());
        }
    }

    /** 携带 HTTP 状态码的认证业务异常，供接口层统一返回失败原因。 */
    public static class AuthException extends RuntimeException {
        private final int status;

        public AuthException(int s, String m) {
            super(m);
            status = s;
        }

        public int status() {
            return status;
        }
    }
}
