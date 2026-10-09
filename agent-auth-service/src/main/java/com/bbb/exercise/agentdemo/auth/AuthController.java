package com.bbb.exercise.agentdemo.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AccountService accounts;
    private final com.bbb.exercise.agentdemo.auth.email.QqEmailService codes;
    private final com.bbb.exercise.agentdemo.auth.email.EmailRequestSource source;

    public AuthController(AuthService auth, AccountService accounts, com.bbb.exercise.agentdemo.auth.email.QqEmailService codes,com.bbb.exercise.agentdemo.auth.email.EmailRequestSource source) { this.auth = auth; this.accounts=accounts; this.codes=codes; this.source=source; }

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody Registration body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            AuthService.User user = accounts.register(body.username(), body.password(), body.qqEmail(), body.code());
            return Map.<String, Object>of("authenticated", false, "userId", auth.publicUserId(user), "username", user.username());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody Credentials body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            AuthService.LoginResult result = auth.login(body.username(), body.password());
            exchange.getResponse().addCookie(cookie(exchange, result.token(), auth.sessionTtl()));
            return Map.<String, Object>of("authenticated", true, "userId", auth.publicUserId(result.user()), "username", result.user().username());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/logout")
    public Mono<Void> logout(ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> {
            auth.logout(exchange);
            exchange.getResponse().addCookie(cookie(exchange, "", Duration.ZERO));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    @GetMapping("/me")
    public Mono<Map<String, Object>> me(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            var identity = auth.resolve(exchange);
            if (identity == null) throw new AuthService.AuthException(401, "未登录");
            Map<String,Object> result=new java.util.LinkedHashMap<>(accounts.email(identity));
            result.put("authenticated",true);result.put("userId",auth.publicUserId(identity));result.put("username",auth.username(identity));return result;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private ResponseCookie cookie(ServerWebExchange exchange, String token, Duration ttl) {
        boolean secure = isSecureRequest(exchange);
        return ResponseCookie.from(auth.sessionCookieName(), token)
                .httpOnly(true).secure(secure).sameSite(secure ? "None" : "Lax")
                .path("/").maxAge(ttl).build();
    }

    private static boolean isSecureRequest(ServerWebExchange exchange) {
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto)
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || Boolean.parseBoolean(System.getenv().getOrDefault("FORCE_COOKIE_SECURE", "false"));
    }

    public record Credentials(String username, String password) {}
    public record Registration(String username,String password,String qqEmail,String code) {}
    public record EmailCode(String qqEmail,String code) {}
    public record CodeRequest(String qqEmail,String purpose) {}

    @PostMapping("/email/code")
    public Mono<Map<String,Object>> code(@RequestBody CodeRequest body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            codes.send(body.qqEmail(),body.purpose(),source.ip(exchange));
            return Map.<String,Object>of("sent",true,"expiresIn",300,"resendAfter",60);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @PostMapping("/email/login")
    public Mono<Map<String,Object>> emailLogin(@RequestBody EmailCode body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            var result=accounts.emailLogin(body.qqEmail(),body.code());
            exchange.getResponse().addCookie(cookie(exchange,result.token(),auth.sessionTtl()));
            return Map.<String,Object>of("authenticated",true,"userId",auth.publicUserId(result.user()),"username",result.user().username());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    @PostMapping("/email/bind")
    public Mono<Map<String,Object>> bind(@RequestBody EmailCode body,ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {var identity=auth.resolve(exchange);accounts.bind(identity,body.qqEmail(),body.code());return accounts.email(identity);})
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
