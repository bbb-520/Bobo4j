package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.auth.email.EmailRequestSource;
import com.bbb.exercise.agentdemo.auth.email.QqEmailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static reactor.core.scheduler.Schedulers.boundedElastic;

/** 浏览器认证入口，处理注册、密码或邮箱登录、退出及会话 Cookie。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AccountService accounts;
    private final QqEmailService codes;
    private final EmailRequestSource source;

    public AuthController(
            AuthService auth,
            AccountService accounts,
            QqEmailService codes,
            EmailRequestSource source) {
        this.auth = auth;
        this.accounts = accounts;
        this.codes = codes;
        this.source = source;
    }

    // 1. 注册入口：创建账户后返回注册结果，会话由后续登录流程签发。
    @PostMapping("/register")
    public Mono<Map<String, Object>> register(
            @RequestBody Registration body, ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            AuthService.User user =
                                    accounts.register(
                                            body.username(),
                                            body.password(),
                                            body.qqEmail(),
                                            body.code());
                            return Map.<String, Object>of(
                                    "authenticated",
                                    false,
                                    "userId",
                                    auth.publicUserId(user),
                                    "username",
                                    user.username());
                        })
                .subscribeOn(boundedElastic());
    }

    // 2. 密码登录入口：把会话令牌写入浏览器 Cookie。
    @PostMapping("/login")
    public Mono<Map<String, Object>> login(
            @RequestBody Credentials body, ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            AuthService.LoginResult result =
                                    auth.login(body.username(), body.password());

                            exchange.getResponse()
                                    .addCookie(cookie(exchange, result.token(), auth.sessionTtl()));

                            return loginView(result);
                        })
                .subscribeOn(boundedElastic());
    }

    // 2.1. 注销入口：撤销服务端会话并清除浏览器 Cookie。
    @PostMapping("/logout")
    public Mono<Void> logout(ServerWebExchange exchange) {
        return Mono.fromRunnable(
                        () -> {
                            auth.logout(exchange);
                            exchange.getResponse().addCookie(cookie(exchange, "", Duration.ZERO));
                        })
                .subscribeOn(boundedElastic())
                .then();
    }

    // 3. 当前账户：组合身份、邮箱绑定状态和实时角色权限。
    @GetMapping("/me")
    public Mono<Map<String, Object>> me(ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            var identity = auth.resolve(exchange);

                            if (identity == null) {
                                throw new AuthService.AuthException(401, "未登录");
                            }

                            Map<String, Object> result =
                                    new java.util.LinkedHashMap<>(accounts.email(identity));
                            var grants =
                                    auth.resolvePublicUser(
                                            auth.publicUserId(identity), identity.tenantId());

                            result.put("roles", grants.roles());
                            result.put("permissions", grants.permissions());
                            result.put("authenticated", true);
                            result.put("userId", auth.publicUserId(identity));
                            result.put("username", auth.username(identity));

                            return result;
                        })
                .subscribeOn(boundedElastic());
    }

    private Map<String, Object> loginView(AuthService.LoginResult result) {
        var identity = auth.resolveSessionToken(result.token());
        return Map.of(
                "authenticated",
                true,
                "userId",
                identity.userId(),
                "username",
                identity.username(),
                "roles",
                identity.roles(),
                "permissions",
                identity.permissions());
    }

    // 4. CSRF 引导：向浏览器提供后续写请求需要携带的令牌和请求头名。
    @GetMapping("/csrf")
    public Mono<Map<String, String>> csrf(ServerWebExchange exchange) {
        Mono<org.springframework.security.web.server.csrf.CsrfToken> token =
                exchange.getAttribute(
                        org.springframework.security.web.server.csrf.CsrfToken.class.getName());

        if (token == null) {
            return Mono.error(new AuthService.AuthException(503, "CSRF 初始化不可用"));
        }

        return token.map(t -> Map.of("token", t.getToken(), "headerName", t.getHeaderName()));
    }

    // 4.1. 会话 Cookie：根据请求安全属性设置 Secure 和 SameSite。
    private ResponseCookie cookie(ServerWebExchange exchange, String token, Duration ttl) {
        boolean secure = isSecureRequest(exchange);
        return ResponseCookie.from(auth.sessionCookieName(), token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .path("/")
                .maxAge(ttl)
                .build();
    }

    private static boolean isSecureRequest(ServerWebExchange exchange) {
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto)
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || Boolean.parseBoolean(
                        System.getenv().getOrDefault("FORCE_COOKIE_SECURE", "false"));
    }

    /** 密码登录请求，携带用户名和待校验的密码。 */
    public record Credentials(String username, String password) {}

    /** 账户注册请求，仅接收账户与邮箱验证字段并隐藏敏感日志内容。 */
    public record Registration(String username, String password, String qqEmail, String code) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new AuthService.AuthException(400, "注册不接受额外字段");
        }

        @Override
        public String toString() {
            return "Registration[REDACTED]";
        }
    }

    /** 邮箱登录或绑定请求，将邮箱地址与一次性验证码配对。 */
    public record EmailCode(String qqEmail, String code) {}

    /** 验证码发送请求，指定收件邮箱和注册、登录或绑定用途。 */
    public record CodeRequest(String qqEmail, String purpose) {}

    // 5. 邮箱验证：按业务用途发送验证码，并使用网关确认的来源参与限流。
    @PostMapping("/email/code")
    public Mono<Map<String, Object>> code(
            @RequestBody CodeRequest body, ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            codes.send(body.qqEmail(), body.purpose(), source.ip(exchange));
                            return Map.<String, Object>of(
                                    "sent", true, "expiresIn", 300, "resendAfter", 60);
                        })
                .subscribeOn(boundedElastic());
    }

    // 5.1. 邮箱验证码登录：复用统一的会话 Cookie 响应。
    @PostMapping("/email/login")
    public Mono<Map<String, Object>> emailLogin(
            @RequestBody EmailCode body, ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            var result = accounts.emailLogin(body.qqEmail(), body.code());

                            exchange.getResponse()
                                    .addCookie(cookie(exchange, result.token(), auth.sessionTtl()));

                            return loginView(result);
                        })
                .subscribeOn(boundedElastic());
    }

    // 5.2. 邮箱绑定入口：仅为当前已登录账户执行绑定。
    @PostMapping("/email/bind")
    public Mono<Map<String, Object>> bind(@RequestBody EmailCode body, ServerWebExchange exchange) {
        return Mono.fromCallable(
                        () -> {
                            var identity = auth.resolve(exchange);

                            accounts.bind(identity, body.qqEmail(), body.code());

                            return accounts.email(identity);
                        })
                .subscribeOn(boundedElastic());
    }
}
