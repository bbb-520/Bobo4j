package com.bbb.exercise.agentdemo.authservice;

import com.bbb.exercise.agentdemo.api.dto.UserIdentityDto;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.model.ModelCapability;
import com.bbb.exercise.agentdemo1_0.model.ModelProfileService;
import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {
    private final AuthService auth;
    private final UserApiKeyService userKeys;
    private final ModelProfileService modelProfiles;
    private final Map<String, byte[]> principalSecrets;

    public InternalAuthController(AuthService auth, UserApiKeyService userKeys, ModelProfileService modelProfiles,
                                  @Value("${app.security.internal-principal-secrets:}") String encodedSecret) {
        this.auth = auth;
        this.userKeys = userKeys;
        this.modelProfiles = modelProfiles;
        try {
            this.principalSecrets = java.util.Arrays.stream((encodedSecret == null ? "" : encodedSecret).split(","))
                    .filter(item -> item.contains("="))
                    .map(item -> item.split("=", 2))
                    .collect(Collectors.toUnmodifiableMap(item -> item[0].trim(), item -> Base64.getDecoder().decode(item[1].trim())));
        } catch (IllegalArgumentException e) { throw new IllegalStateException("内部 principal 密钥必须是 keyId=Base64[,keyId=Base64]", e); }
    }

    /** Test/embedding constructor for the identity-only boundary. */
    public InternalAuthController(AuthService auth, String encodedSecret) {
        this(auth, null, null, encodedSecret);
    }

    @GetMapping("/users/{userId}")
    public UserIdentityDto getUser(@PathVariable String userId,
                                   @RequestHeader(value = "X-Internal-Principal") String token) {
        var principal = verify(token, "GET /internal/auth/users/" + userId);
        assertSubject(principal, userId);
        String tenantId = principal.tenant();
        var identity = auth.resolvePublicUser(userId, tenantId);
        if (identity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return new UserIdentityDto(identity.userId(), identity.tenantId(), true);
    }

    @GetMapping("/session")
    public UserIdentityDto session(ServerWebExchange exchange,
                                   @RequestHeader(value = "X-Internal-Principal") String token) {
        verify(token, "GET /internal/auth/session");
        var cookie = exchange.getRequest().getCookies().getFirst(auth.sessionCookieName());
        if (cookie == null || cookie.getValue().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话无效");
        var identity = auth.resolveSessionToken(cookie.getValue());
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话无效");
        return new UserIdentityDto(identity.userId(), identity.tenantId(), true);
    }

    @GetMapping("/users/{userId}/api-key")
    public UserApiKeyService.UserApiKeys apiKey(@PathVariable String userId,
                                                @RequestHeader("X-Internal-Principal") String token) {
        var principal = verify(token, "GET /internal/auth/users/" + userId + "/api-key");
        assertSubject(principal, userId);
        var identity = auth.identityForPublicUser(userId, principal.tenant());
        if (identity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return userKeys.get(identity);
    }

    @GetMapping("/users/{userId}/model-profiles/{capability}")
    public ModelProfileService.SelectedModel model(@PathVariable String userId, @PathVariable String capability,
                                                   @RequestHeader("X-Internal-Principal") String token) {
        var principal = verify(token, "GET /internal/auth/users/" + userId + "/model-profiles/" + capability);
        assertSubject(principal, userId);
        var identity = auth.identityForPublicUser(userId, principal.tenant());
        if (identity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return modelProfiles.resolve(identity, ModelCapability.parse(capability));
    }

    private SignedPrincipal.Scoped verify(String token, String operation) {
        try { return SignedPrincipal.verifyScoped(token, principalSecrets, Instant.now(),
                "agent-auth-service", operation); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部身份签名无效"); }
    }

    private static void assertSubject(SignedPrincipal.Scoped principal, String userId) {
        if (!userId.equals(principal.subject()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内部 principal subject 不匹配");
    }
}
