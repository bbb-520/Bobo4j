package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Locale;

/** Shared bounded HTTP transport. Call blocking helpers only on boundedElastic or worker threads. */
@Component
public class InternalServiceClient {
    private final WebClient auth;
    private final WebClient media;
    private final PrincipalKeyRing keys;
    private final String caller;
    private final String cookieName;
    private final String tenant;

    public InternalServiceClient(@Value("${app.auth.base-url:http://127.0.0.1:18081}") String authUrl,
                                 @Value("${app.media.base-url:http://127.0.0.1:18082}") String mediaUrl,
                                 @Value("${spring.application.name}") String caller,
                                 @Value("${app.security.internal-principal-secrets:}") String secrets,
                                 @Value("${app.security.internal-principal-active-key-id:current}") String active,
                                 @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName,
                                 @Value("${app.security.default-tenant-id:local}") String tenant) {
        this(authUrl,mediaUrl,caller,secrets,active,cookieName,tenant,WebClient.builder());
    }
    @Autowired
    public InternalServiceClient(@Value("${app.auth.base-url:http://127.0.0.1:18081}") String authUrl,
                                 @Value("${app.media.base-url:http://127.0.0.1:18082}") String mediaUrl,
                                 @Value("${spring.application.name}") String caller,
                                 @Value("${app.security.internal-principal-secrets:}") String secrets,
                                 @Value("${app.security.internal-principal-active-key-id:current}") String active,
                                 @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName,
                                 @Value("${app.security.default-tenant-id:local}") String tenant,WebClient.Builder builder) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.media = builder.clone().baseUrl(mediaUrl).build();
        this.keys = new PrincipalKeyRing(secrets, active);
        this.caller = caller;
        this.cookieName = cookieName;
        this.tenant = tenant;
    }

    public Mono<Identity> session(String token) {
        if (token == null || token.isBlank() || token.length() > 512) return Mono.empty();
        String path = "/internal/auth/session";
        return Mono.defer(() -> exchange(auth.get().uri(path).cookie(cookieName, token)
                .header("X-Internal-Principal", keys.sign(caller, "session", tenant,
                        "agent-auth-service", "GET " + path)), Identity.class))
                .onErrorResume(ResponseStatusException.class, error -> error.getStatusCode().value() == 401
                        ? Mono.empty() : Mono.error(error));
    }

    public <T> Mono<T> getAuth(String path, String subject, String tenant, Class<T> type) {
        return Mono.defer(() -> exchange(auth.get().uri(path).header("X-Internal-Principal",
                keys.sign(caller, subject, tenant, "agent-auth-service", "GET " + path)), type));
    }
    public <T> Mono<T> postAuth(String path,String subject,String tenant,Object body,Class<T> type) {
        return Mono.defer(() -> exchange(auth.post().uri(path).header("X-Internal-Principal",
                keys.sign(caller,subject,tenant,"agent-auth-service","POST "+path)).bodyValue(body),type));
    }

    public Mono<Credential> credential(String publicUserId, String tenant) {
        String path = "/internal/auth/users/" + safe(publicUserId) + "/api-key";
        return getAuth(path, publicUserId, tenant, Credential.class);
    }

    public Mono<Model> model(String publicUserId, String tenant, String capability) {
        String normalized = capability == null ? "" : capability.toUpperCase(Locale.ROOT);
        String path = "/internal/auth/users/" + safe(publicUserId) + "/model-profiles/" + safe(normalized);
        return getAuth(path, publicUserId, tenant, Model.class);
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:@-]{1,128}"))
            throw new IllegalArgumentException("内部请求资源标识无效");
        return value;
    }

    public <T> Mono<T> getMedia(String path, String subject, String tenant, Class<T> type) {
        return Mono.defer(() -> exchange(media.get().uri(path).header("X-Internal-Principal",
                keys.sign(caller, subject, tenant, "agent-media-service", "GET " + path)), type));
    }

    public <T> Mono<T> postMedia(String path, String subject, String tenant, Object body, Class<T> type) {
        return Mono.defer(() -> exchange(media.post().uri(path).header("X-Internal-Principal",
                keys.sign(caller, subject, tenant, "agent-media-service", "POST " + path)).bodyValue(body), type));
    }

    public Mono<MediaAsset> mediaAsset(String assetId, String subject, String tenant) {
        String path = "/internal/media/assets/" + safe(assetId);
        return getMedia(path, subject, tenant, MediaAsset.class);
    }

    public Mono<PublishSource> mediaPublishSource(String jobId, String subject, String tenant) {
        String path = "/internal/media/image-jobs/" + safe(jobId) + "/publish-source";
        return getMedia(path, subject, tenant, PublishSource.class);
    }

    public Mono<MediaJobView> createMediaJob(String conversationId, String prompt, String assetId,
                                             String subject, String tenant) {
        return postMedia("/internal/media/image-jobs", subject, tenant,
                new CreateMediaJobRequest(conversationId, prompt, assetId), MediaJobView.class);
    }

    private <T> Mono<T> exchange(WebClient.RequestHeadersSpec<?> request, Class<T> type) {
        return request.exchangeToMono(response -> {
            if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(type);
            int status = response.statusCode().value();
            // Never include upstream body (credentials or provider details) in an exception.
            return response.releaseBody().then(Mono.<T>error(new ResponseStatusException(
                    status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.valueOf(status), "内部服务请求失败")));
        }).timeout(Duration.ofSeconds(5))
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务暂不可用"));
    }

    public record Identity(String userId, String tenantId, boolean authenticated, String username) {}
    public record Model(String provider, String capability, String model, String apiKey,String baseUrl,String credentialId) {
        public Model(String provider,String capability,String model,String apiKey) {this(provider,capability,model,apiKey,null,"platform");}
        @Override public String toString() { return "Model[provider=" + provider + ",capability=" + capability + ",model=" + model + "]"; }
    }
    public record Credential(String qwenApiKey) {
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
    public record MediaAsset(String assetId, String objectKey, String mimeType, long fileSize, String downloadUrl) {}
    public record PublishSource(String jobId, String outputObjectKey, String prompt) {}
    public record CreateMediaJobRequest(String conversationId, String prompt, String assetId) {}
    public record MediaJobView(String jobId, String status, String prompt, String mode,
                               java.time.LocalDateTime createdAt, java.time.LocalDateTime startedAt,
                               java.time.LocalDateTime completedAt, String imageUrl,
                               String rationale, String error) {}
}
