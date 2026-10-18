package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.runtime.client.AuthModelClient;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Zine admission/composition metadata over the shared Media job and receipt engine. */
@Service
@RequiredArgsConstructor
public class ZineMediaJobService {
    private final JdbcTemplate jdbc;
    private final ImageJobAdmission admission;
    private final ImageJobService jobs;
    private final AuthModelClient models;
    private static final int MAX_SOURCE_CHARS = 28 * 1024 * 1024;

    public String create(ChatIdentity owner, ZineRequest request) {
        requireOwner(owner);
        requireId(request.requestId());
        if (request.imageReference() == null || request.imageReference().length() > MAX_SOURCE_CHARS
                || !request.imageReference().matches("(?s)data:image/(png|jpeg|webp|bmp|tiff|gif);base64,[A-Za-z0-9+/=]+")
                || request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 16000
                || request.mode() == null || request.mode().length() > 32
                || request.language() == null || request.language().length() > 32
                || request.rationale() == null || request.rationale().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zine 参数无效或过大");
        }
        Stored previous = find(owner, request.requestId());
        if (previous != null) return replay(previous, request);
        var selected = models.resolve(owner, ModelCapability.IMAGE);
        if (selected == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片模型未配置");
        String model = selected.model(), provider = selected.provider().name();
        String fingerprint = fingerprint(request, model, provider);
        return admission.admit(owner, () -> {
            var winner = find(owner, request.requestId());
            return winner == null ? null : replay(winner, request);
        }, () -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO image_job(id,tenant_id,user_id,conversation_id,output_object_key,mode,language,prompt,provider,model,status,created_at,updated_at,expires_at) VALUES (?,?,?,?,NULL,'zine',?,?,?,?,'QUEUED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),TIMESTAMPADD(DAY,30,CURRENT_TIMESTAMP(3)))",
                    id, owner.tenantId(), owner.userId(), id, request.language(), request.prompt(), provider, model);
            jdbc.update("INSERT INTO zine_media_request(job_id,tenant_id,user_id,request_id,request_fingerprint,mode,rationale,source_reference,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
                    id, owner.tenantId(), owner.userId(), request.requestId(), fingerprint, request.mode(), request.rationale(), request.imageReference());
            return id;
        });
    }

    public Mono<ZineResult> generate(ChatIdentity owner, ZineRequest request) {
        return Mono.fromCallable(() -> create(owner, request)).subscribeOn(Schedulers.boundedElastic())
                .flatMap(id -> await(owner, request.requestId(), 0));
    }

    private Mono<ZineResult> await(ChatIdentity owner, String requestId, int count) {
        return Mono.fromCallable(() -> get(owner, requestId)).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(Pending.class, pending -> count >= 180
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "任务仍在处理中，请使用相同 requestId 恢复"))
                        : Mono.delay(Duration.ofSeconds(1)).then(Mono.defer(() -> await(owner, requestId, count + 1))));
    }

    public ZineResult get(ChatIdentity owner, String requestId) {
        requireOwner(owner); requireId(requestId);
        Stored stored = find(owner, requestId);
        if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "生成结果不存在");
        String status = jdbc.queryForObject("SELECT status FROM image_job WHERE id=?", String.class, stored.jobId());
        if ("FAILED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已结束；不明供应商结果不会自动重复调用，请核对消费记录");
        if (!"SUCCEEDED".equals(status)) throw new Pending();
        var receipt = jobs.receipt(owner, stored.jobId());
        if (receipt == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "生成回执暂不可用");
        return new ZineResult(requestId, new ZineResponse(stored.mode(), stored.model(), receipt.imageUrl(),
                stored.rationale(), receipt.providerRequestId(), receipt.inputTokens(), receipt.outputTokens()));
    }

    public List<String> list(ChatIdentity owner) {
        requireOwner(owner);
        return jdbc.query("SELECT request_id FROM zine_media_request WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT 50",
                (rs,n) -> rs.getString(1), owner.tenantId(), owner.userId());
    }

    /** Source payload is bounded and is never needed again after dispatch/receipt or terminal expiry. */
    @Scheduled(fixedDelay = 60000)
    public void cleanSources() {
        jdbc.update("UPDATE zine_media_request SET source_reference=NULL WHERE source_reference IS NOT NULL AND job_id IN (SELECT id FROM image_job WHERE result_ready=TRUE OR status IN ('FAILED','SUCCEEDED') OR expires_at<=CURRENT_TIMESTAMP(3))");
    }

    private Stored find(ChatIdentity owner, String requestId) {
        var rows = jdbc.query("SELECT z.job_id,z.request_fingerprint,z.mode,z.rationale,j.model,j.provider FROM zine_media_request z JOIN image_job j ON j.id=z.job_id WHERE z.tenant_id=? AND z.user_id=? AND z.request_id=?",
                (rs,n) -> new Stored(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)), owner.tenantId(),owner.userId(),requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private String replay(Stored stored, ZineRequest request) {
        if (!stored.fingerprint().equals(fingerprint(request, stored.model(), stored.provider())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一 requestId 不能用于不同的生成参数");
        return stored.jobId();
    }
    private static String fingerprint(ZineRequest r, String model, String provider) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(r.imageReference(),r.mode(),r.language(),r.prompt(),r.rationale(),model,provider)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId 必须是小写 UUID");
    }
    private static void requireOwner(ChatIdentity identity) {
        if (identity == null || !identity.authenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private record Stored(String jobId, String fingerprint, String mode, String rationale, String model, String provider) {}
    private static final class Pending extends ResponseStatusException {
        Pending() { super(HttpStatus.CONFLICT, "任务仍在处理中，请稍后用同一 requestId 恢复"); }
    }
}
