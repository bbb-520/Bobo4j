package com.bbb.exercise.agentdemo.authservice;

import com.bbb.exercise.agentdemo.api.dto.UserIdentityDto;
import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.auth.model.ModelProfileService;
import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 服务间认证接口，校验调用签名后提供用户、会话及模型凭据查询。 */
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {
    private final AuthService auth;
    private final UserApiKeyService userKeys;
    private final ModelProfileService modelProfiles;
    private final Map<String, byte[]> principalSecrets;

    @Autowired
    public InternalAuthController(
            AuthService auth,
            UserApiKeyService userKeys,
            ModelProfileService modelProfiles,
            @Value("${app.security.internal-principal-secrets:}") String encodedSecret) {
        this.auth = auth;
        this.userKeys = userKeys;
        this.modelProfiles = modelProfiles;

        try {
            this.principalSecrets =
                    java.util.Arrays.stream((encodedSecret == null ? "" : encodedSecret).split(","))
                            .filter(item -> item.contains("="))
                            .map(item -> item.split("=", 2))
                            .collect(
                                    Collectors.toUnmodifiableMap(
                                            item -> item[0].trim(),
                                            item -> Base64.getDecoder().decode(item[1].trim())));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("内部 principal 密钥必须是 keyId=Base64[,keyId=Base64]", e);
        }
    }

    /** Test/embedding constructor for the identity-only boundary. */
    public InternalAuthController(AuthService auth, String encodedSecret) {
        this(auth, null, null, encodedSecret);
    }

    // 1. 内部用户查询：验证服务签名和请求主体后返回公开身份。
    @GetMapping("/users/{userId}")
    public Mono<UserIdentityDto> getUser(
            @PathVariable String userId,
            @RequestHeader(value = "X-Internal-Principal") String token) {
        // 身份查询是阻塞的 MyBatis 调用，必须离开 Reactor 的计算/事件循环调度器。
        return Mono.fromCallable(
                        () -> {
                            var principal = verify(token, "GET /internal/auth/users/" + userId);

                            assertSubject(principal, userId);
                            String tenantId = principal.tenant();
                            var identity = auth.resolvePublicUser(userId, tenantId);

                            if (identity == null) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
                            }

                            return new UserIdentityDto(
                                    identity.userId(),
                                    identity.tenantId(),
                                    true,
                                    identity.username(),
                                    identity.roles(),
                                    identity.permissions());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 2. 内部会话查询：供网关及业务服务确认浏览器身份。
    @GetMapping("/session")
    public Mono<UserIdentityDto> session(
            ServerWebExchange exchange,
            @RequestHeader(value = "X-Internal-Principal") String token) {
        return Mono.fromCallable(
                        () -> {
                            var principal = verify(token, "GET /internal/auth/session");

                            if (!"session".equals(principal.subject())) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "会话操作主体不匹配");
                            }

                            var cookie =
                                    exchange.getRequest()
                                            .getCookies()
                                            .getFirst(auth.sessionCookieName());

                            if (cookie == null || cookie.getValue().isBlank()) {
                                throw new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "会话无效");
                            }

                            var identity = auth.resolveSessionToken(cookie.getValue());

                            if (identity == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "会话无效");
                            }

                            if (!identity.tenantId().equals(principal.tenant())) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "会话租户不匹配");
                            }

                            return new UserIdentityDto(
                                    identity.userId(),
                                    identity.tenantId(),
                                    true,
                                    identity.username(),
                                    identity.roles(),
                                    identity.permissions());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 3. 内部凭据查询：凭据仅经签名内部协议交给受信调用者。
    @GetMapping("/users/{userId}/api-key")
    public Mono<UserApiKeyService.UserApiKeys> apiKey(
            @PathVariable String userId, @RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(
                        () -> {
                            var principal =
                                    verify(
                                            token,
                                            "GET /internal/auth/users/" + userId + "/api-key");

                            assertSubject(principal, userId);
                            var identity = auth.identityForPublicUser(userId, principal.tenant());

                            if (identity == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "用户不存在");
                            }

                            return userKeys.get(identity);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 3.1. 内部模型查询：按能力解析配置，并核对签名主体与目标用户。
    @GetMapping("/users/{userId}/model-profiles/{capability}")
    public Mono<ModelProfileService.SelectedModel> model(
            @PathVariable String userId,
            @PathVariable String capability,
            @RequestHeader("X-Internal-Principal") String token) {
        return Mono.fromCallable(
                        () -> {
                            var principal =
                                    verify(
                                            token,
                                            "GET /internal/auth/users/"
                                                    + userId
                                                    + "/model-profiles/"
                                                    + capability);

                            assertSubject(principal, userId);
                            var identity = auth.identityForPublicUser(userId, principal.tenant());

                            if (identity == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "用户不存在");
                            }

                            return modelProfiles.resolve(
                                    identity, ModelCapability.parse(capability));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 4. 调用边界：验证签名、目标服务及请求作用域，阻止重放到其他接口。
    private SignedPrincipal.Scoped verify(String token, String operation) {
        try {
            return SignedPrincipal.verifyScoped(
                    token, principalSecrets, Instant.now(), "agent-auth-service", operation);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部身份签名无效");
        }
    }

    private static void assertSubject(SignedPrincipal.Scoped principal, String userId) {
        if (!userId.equals(principal.subject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内部 principal subject 不匹配");
        }
    }
}
