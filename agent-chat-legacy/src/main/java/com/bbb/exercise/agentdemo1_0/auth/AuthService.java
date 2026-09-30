package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.config.AppProperties;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/** Registration, login and server-side session management. */
@Service
public class AuthService {
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, PasswordHasher passwords, AppProperties properties) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.properties = properties;
    }

    public User register(String username, String password) {
        validate(username, password);
        try {
            jdbc.update("INSERT INTO app_user(username,password_hash,created_at) VALUES (?,?,?)",
                    username.trim(), passwords.hash(password), LocalDateTime.now());
            return find(username.trim());
        } catch (DuplicateKeyException e) {
            throw new AuthException(409, "用户名已存在");
        }
    }

    public LoginResult login(String username, String password) {
        validate(username, password);
        User user = find(username.trim());
        if (user == null || !passwords.matches(password, user.passwordHash())) {
            throw new AuthException(401, "用户名或密码错误");
        }
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO auth_session(user_id,token_hash,expires_at,created_at) VALUES (?,?,?,?)",
                user.id(), hash(token), LocalDateTime.now().plus(SESSION_TTL), LocalDateTime.now());
        return new LoginResult(user, token);
    }

    public ChatIdentity resolve(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(properties.getSecurity().getSessionCookieName());
        if (cookie == null || cookie.getValue().isBlank()) return null;
        var rows = jdbc.query("SELECT u.id,u.username FROM auth_session s JOIN app_user u ON u.id=s.user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>?", (rs, n) -> new Object[]{
                        rs.getLong("id"), rs.getString("username")}, hash(cookie.getValue()), LocalDateTime.now());
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new ChatIdentity(properties.getSecurity().getDefaultTenantId(), "user:" + row[0], true);
    }

    public void logout(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(properties.getSecurity().getSessionCookieName());
        if (cookie != null) jdbc.update("DELETE FROM auth_session WHERE token_hash=?", hash(cookie.getValue()));
    }

    public String sessionCookieName() { return properties.getSecurity().getSessionCookieName(); }
    public Duration sessionTtl() { return SESSION_TTL; }

    public long requireUserId(ChatIdentity identity) {
        if (identity == null || !identity.authenticated() || !identity.userId().startsWith("user:")) {
            throw new AuthException(401, "请先登录");
        }
        return Long.parseLong(identity.userId().substring("user:".length()));
    }

    public String username(ChatIdentity identity) {
        long userId = requireUserId(identity);
        return jdbc.queryForObject("SELECT username FROM app_user WHERE id=?", String.class, userId);
    }

    /** Stable public identifier derived from the internal user id without exposing the sequence value. */
    public String publicUserId(User user) {
        return UUID.nameUUIDFromBytes(("bobo:user:" + user.id()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public String publicUserId(ChatIdentity identity) {
        long userId = requireUserId(identity);
        return publicUserId(new User(userId, username(identity), ""));
    }

    private User find(String username) {
        var rows = jdbc.query("SELECT id,username,password_hash FROM app_user WHERE username=?", (rs, n) ->
                new User(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")), username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void validate(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_.@-]{3,64}"))
            throw new AuthException(400, "用户名需为 3-64 位字母、数字或 _ . @ -");
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new AuthException(400, "密码长度需为 8-128 位");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record User(long id, String username, String passwordHash) {}
    public record LoginResult(User user, String token) {}
    public static class AuthException extends RuntimeException {
        private final int status;
        public AuthException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
