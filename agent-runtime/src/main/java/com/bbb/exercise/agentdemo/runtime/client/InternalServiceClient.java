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

    public InternalServiceClient(
            @Value("${app.auth.base-url:http://127.0.0.1:18081}") String authUrl,
            @Value("${app.media.base-url:http://127.0.0.1:18082}") String mediaUrl,
            @Value("${spring.application.name}") String caller,
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active,
            @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName,
            @Value("${app.security.default-tenant-id:local}") String tenant) {
        this(authUrl, mediaUrl, caller, secrets, active, cookieName, tenant, WebClient.builder());
    }

    @Autowired
    public InternalServiceClient(
            @Value("${app.auth.base-url:http://127.0.0.1:18081}") String authUrl,
            @Value("${app.media.base-url:http://127.0.0.1:18082}") String mediaUrl,
            @Value("${spring.application.name}") String caller,
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active,
            @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName,
            @Value("${app.security.default-tenant-id:local}") String tenant,
            WebClient.Builder builder) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.media = builder.clone().baseUrl(mediaUrl).build();
        this.keys = new PrincipalKeyRing(secrets, active);
        this.caller = caller;
        this.cookieName = cookieName;
        this.tenant = tenant;
    }

    // 1. 会话协议：向 Auth 查询令牌对应的实时用户身份。
    public Mono<Identity> session(String token) {
        if (token == null || token.isBlank() || token.length() > 512) {
            return Mono.empty();
        }

        String path = "/internal/auth/session";

        return Mono.defer(
                        () ->
                                exchange(
                                        auth.get()
                                                .uri(path)
                                                .cookie(cookieName, token)
                                                .header(
                                                        "X-Internal-Principal",
                                                        keys.sign(
                                                                caller,
                                                                "session",
                                                                tenant,
                                                                "agent-auth-service",
                                                                "GET " + path)),
                                        Identity.class))
                .onErrorResume(
                        ResponseStatusException.class,
                        error ->
                                error.getStatusCode().value() == 401
                                        ? Mono.empty()
                                        : Mono.error(error));
    }

    public <T> Mono<T> getAuth(String path, String subject, String tenant, Class<T> type) {
        return Mono.defer(
                () ->
                        exchange(
                                auth.get()
                                        .uri(path)
                                        .header(
                                                "X-Internal-Principal",
                                                keys.sign(
                                                        caller,
                                                        subject,
                                                        tenant,
                                                        "agent-auth-service",
                                                        "GET " + path)),
                                type));
    }

    public <T> Mono<T> postAuth(
            String path, String subject, String tenant, Object body, Class<T> type) {
        return Mono.defer(
                () ->
                        exchange(
                                auth.post()
                                        .uri(path)
                                        .header(
                                                "X-Internal-Principal",
                                                keys.sign(
                                                        caller,
                                                        subject,
                                                        tenant,
                                                        "agent-auth-service",
                                                        "POST " + path))
                                        .bodyValue(body),
                                type));
    }

    // 2. 模型配置协议：通过签名内部请求取得当前平台凭据和能力配置。
    public Mono<Credential> credential(String publicUserId, String tenant) {
        String path = "/internal/auth/users/" + safe(publicUserId) + "/api-key";
        return getAuth(path, publicUserId, tenant, Credential.class);
    }

    public Mono<Model> model(String publicUserId, String tenant, String capability) {
        String normalized = capability == null ? "" : capability.toUpperCase(Locale.ROOT);
        String path =
                "/internal/auth/users/"
                        + safe(publicUserId)
                        + "/model-profiles/"
                        + safe(normalized);

        return getAuth(path, publicUserId, tenant, Model.class);
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:@-]{1,128}")) {
            throw new IllegalArgumentException("内部请求资源标识无效");
        }

        return value;
    }

    public <T> Mono<T> getMedia(String path, String subject, String tenant, Class<T> type) {
        return Mono.defer(
                () ->
                        exchange(
                                media.get()
                                        .uri(path)
                                        .header(
                                                "X-Internal-Principal",
                                                keys.sign(
                                                        caller,
                                                        subject,
                                                        tenant,
                                                        "agent-media-service",
                                                        "GET " + path)),
                                type));
    }

    public <T> Mono<T> postMedia(
            String path, String subject, String tenant, Object body, Class<T> type) {
        return Mono.defer(
                () ->
                        exchange(
                                media.post()
                                        .uri(path)
                                        .header(
                                                "X-Internal-Principal",
                                                keys.sign(
                                                        caller,
                                                        subject,
                                                        tenant,
                                                        "agent-media-service",
                                                        "POST " + path))
                                        .bodyValue(body),
                                type));
    }

    // 3. 媒体读取协议：由 Media 核对归属后返回图片资产信息。
    public Mono<MediaAsset> mediaAsset(String assetId, String subject, String tenant) {
        String path = "/internal/media/assets/" + safe(assetId);
        return getMedia(path, subject, tenant, MediaAsset.class);
    }

    // 3.1. 发布来源协议：只获取当前用户已成功任务的可发布输出。
    public Mono<PublishSource> mediaPublishSource(String jobId, String subject, String tenant) {
        String path = "/internal/media/image-jobs/" + safe(jobId) + "/publish-source";
        return getMedia(path, subject, tenant, PublishSource.class);
    }

    // 3.2. 图片任务协议：传递用户和租户作用域创建异步生成任务。
    public Mono<MediaJobView> createMediaJob(
            String conversationId, String prompt, String assetId, String subject, String tenant) {
        return postMedia(
                "/internal/media/image-jobs",
                subject,
                tenant,
                new CreateMediaJobRequest(conversationId, prompt, assetId),
                MediaJobView.class);
    }

    // 4. HTTP 签名边界：统一构造带目标接口作用域的内部身份签名。
    private <T> Mono<T> exchange(WebClient.RequestHeadersSpec<?> request, Class<T> type) {
        return request.exchangeToMono(
                        response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(type);
                            }

                            int status = response.statusCode().value();

                            // Never include upstream body (credentials or provider details) in an
                            // exception.
                            return response.releaseBody()
                                    .then(
                                            Mono.<T>error(
                                                    new ResponseStatusException(
                                                            status >= 500
                                                                    ? HttpStatus.SERVICE_UNAVAILABLE
                                                                    : HttpStatus.valueOf(status),
                                                            "内部服务请求失败")));
                        })
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(
                        error -> !(error instanceof ResponseStatusException),
                        error ->
                                new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE, "内部服务暂不可用"));
    }

    /** 认证服务返回的可信用户视图，包含租户、认证状态及当前角色权限。 */
    public record Identity(
            String userId,
            String tenantId,
            boolean authenticated,
            String username,
            java.util.Set<String> roles,
            java.util.Set<String> permissions) {
        public Identity(String id, String tenant, boolean authenticated, String username) {
            this(id, tenant, authenticated, username, java.util.Set.of(), java.util.Set.of());
        }
    }

    /** 服务间传递的模型选择结果，关联供应商、能力、端点和受保护凭据。 */
    public record Model(
            String provider,
            String capability,
            String model,
            String apiKey,
            String baseUrl,
            String credentialId) {
        public Model(String provider, String capability, String model, String apiKey) {
            this(provider, capability, model, apiKey, null, "platform");
        }

        @Override
        public String toString() {
            return "Model[provider="
                    + provider
                    + ",capability="
                    + capability
                    + ",model="
                    + model
                    + "]";
        }
    }

    /** 认证服务返回的内部凭据载荷，避免通过字符串输出泄露密钥。 */
    public record Credential(String qwenApiKey) {
        @Override
        public String toString() {
            return "Credential[REDACTED]";
        }
    }

    /** 媒体服务返回的图片资产信息，包含对象属性和临时下载地址。 */
    public record MediaAsset(
            String assetId, String objectKey, String mimeType, long fileSize, String downloadUrl) {}

    /** 媒体服务确认可发布的成品来源，携带任务号、输出对象和提示词。 */
    public record PublishSource(String jobId, String outputObjectKey, String prompt) {}

    /** 跨服务图片任务请求，将会话、生成提示词与素材资产关联。 */
    public record CreateMediaJobRequest(String conversationId, String prompt, String assetId) {}

    /** 跨服务图片任务状态视图，返回任务进度、成品地址及失败信息。 */
    public record MediaJobView(
            String jobId,
            String status,
            String prompt,
            String mode,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime startedAt,
            java.time.LocalDateTime completedAt,
            String imageUrl,
            String rationale,
            String error) {}
}
