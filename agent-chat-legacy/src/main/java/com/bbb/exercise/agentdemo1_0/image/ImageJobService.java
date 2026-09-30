package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.config.OssProperties;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.model.ModelCapability;
import com.bbb.exercise.agentdemo1_0.model.ModelProfileService;
import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 图片生成任务的持久化、所有权校验和状态转换。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageJobService {

    private static final int MAX_PROMPT_CODE_POINTS = 4000;

    private final JdbcTemplate jdbc;
    private final ImageAssetService assets;
    private final OssProperties ossProperties;
    private final OssStorageService storage;
    private final UserApiKeyService userKeys;
    private final ModelProfileService modelProfiles;

    public JobView create(ChatIdentity identity, String conversationId, String question,
                          List<ChatAttachmentRequest> attachments) {
        if (identity == null) throw new IllegalArgumentException("身份不能为空");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (attachments == null || attachments.isEmpty()) throw new IllegalArgumentException("请先上传一张照片");
        if (attachments.size() > 1) throw new IllegalArgumentException("当前一次只支持一张照片");
        ChatAttachmentRequest attachment = attachments.get(0);
        if (attachment == null || attachment.getAssetId() == null || attachment.getAssetId().isBlank()) {
            throw new IllegalArgumentException("图片资产 ID 不能为空");
        }
        if (question != null && question.codePointCount(0, question.length()) > MAX_PROMPT_CODE_POINTS) {
            throw new IllegalArgumentException("问题过长（上限 " + MAX_PROMPT_CODE_POINTS + " 字符）");
        }
        ModelProfileService.SelectedModel selected = modelProfiles.resolve(identity, ModelCapability.IMAGE);
        ModelProvider provider = selected == null ? ModelProvider.QWEN : selected.provider();
        String model = selected == null ? "qwen-image-3.0-pro" : selected.model();
        if (selected == null && !userKeys.get(identity).hasQwen()) {
            throw new IllegalStateException("请先在用户页配置图片生成 Agent 和 API Key");
        }
        ImageAssetService.AssetRecord source = assets.requireReady(identity, attachment.getAssetId());
        String jobId = UUID.randomUUID().toString();
        String ownerPart = source.objectKey().split("/").length > 1 ? source.objectKey().split("/")[1] : "private";
        LocalDateTime now = LocalDateTime.now();
        String outputKey = ossProperties.getOutputPrefix() + "/" + ownerPart + "/"
                + now.toLocalDate() + "/" + jobId + ".png";
        String prompt = question == null || question.isBlank() ? "请根据这张照片进行一次有创意的二次生成。" : question.trim();
        String mode = inferMode(prompt);
        jdbc.update("INSERT INTO image_job(id,tenant_id,user_id,conversation_id,source_asset_id,source_object_key,output_object_key,mode,language,prompt,provider,model,status,created_at,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?)",
                jobId, identity.tenantId(), identity.userId(), conversationId, source.id(), source.objectKey(),
                outputKey, mode, "chinese", prompt, provider.name(), model, now, now.plusDays(30));
        log.info("[image-job] task_received jobId={} conversationId={} receivedAt={} mode={}",
                jobId, conversationId, now, mode);
        return view(identity, jobId);
    }

    public JobRecord claimNext() {
        List<JobRecord> jobs = jdbc.query("SELECT * FROM image_job WHERE status='QUEUED' AND expires_at>? ORDER BY created_at LIMIT 1",
                (rs, rowNum) -> map(rs), LocalDateTime.now());
        if (jobs.isEmpty()) return null;
        JobRecord job = jobs.get(0);
        LocalDateTime startedAt = LocalDateTime.now();
        int updated = jdbc.update("UPDATE image_job SET status='PROCESSING',started_at=?,attempt_count=attempt_count+1 WHERE id=? AND status='QUEUED'",
                startedAt, job.id());
        if (updated == 1) {
            log.info("[image-job] task_claimed jobId={} startedAt={}", job.id(), startedAt);
        }
        return updated == 1 ? job : null;
    }

    public void succeed(String jobId, String outputKey, String rationale, String providerRequestId) {
        LocalDateTime completedAt = LocalDateTime.now();
        int updated = jdbc.update("UPDATE image_job SET status='SUCCEEDED',output_object_key=?,rationale=?,provider_request_id=?,completed_at=?,error_message=NULL "
                        + "WHERE id=? AND status='PROCESSING'",
                outputKey, rationale, providerRequestId, completedAt, jobId);
        if (updated != 1) {
            log.warn("[image-job] task_completion_ignored jobId={} completedAt={} reason=unexpected_status", jobId, completedAt);
            return;
        }
        log.info("[image-job] task_completed jobId={} completedAt={} result=SUCCEEDED", jobId, completedAt);
    }

    public void fail(String jobId, String message, int maxAttempts) {
        String safe = message == null ? "图片生成失败" : message.replaceAll("[\\r\\n]+", " ");
        if (safe.length() > 1000) safe = safe.substring(0, 1000);
        int attempts = Math.max(1, maxAttempts);
        LocalDateTime completedAt = LocalDateTime.now();
        int updated = jdbc.update("UPDATE image_job SET status=CASE WHEN attempt_count < ? THEN 'QUEUED' ELSE 'FAILED' END,"
                        + "error_message=?,completed_at=CASE WHEN attempt_count < ? THEN NULL ELSE ? END "
                        + "WHERE id=? AND status='PROCESSING'",
                attempts, safe, attempts, completedAt, jobId);
        if (updated != 1) {
            log.warn("[image-job] task_failure_ignored jobId={} completedAt={} reason=unexpected_status", jobId, completedAt);
            return;
        }
        log.info("[image-job] task_completed jobId={} completedAt={} result={} error={}",
                jobId, completedAt, "retry_or_failed", safe);
    }

    public JobView get(ChatIdentity identity, String jobId) { return view(identity, jobId); }

    /** Resolve the publishable image from a trusted, successful job owned by this identity. */
    public PublishSource requirePublishableOutput(ChatIdentity identity, String jobId) {
        JobRecord job = requireOwnedJob(identity, jobId);
        if (!"SUCCEEDED".equals(job.status()) || job.outputObjectKey() == null || job.outputObjectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有成功生成的图片可以发布");
        }
        return new PublishSource(job.id(), job.outputObjectKey());
    }

    public String downloadUrl(ChatIdentity identity, String jobId) {
        JobRecord job = requireOwnedJob(identity, jobId);
        if (!"SUCCEEDED".equals(job.status()) || job.outputObjectKey() == null || job.outputObjectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有成功生成的图片可以下载");
        }
        return storage.signedDownloadUrl(job.outputObjectKey(), "bobo-" + job.id() + ".png", Duration.ofMinutes(5));
    }

    public List<JobView> conversation(ChatIdentity identity, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空");
        }
        return jdbc.query("SELECT * FROM image_job WHERE tenant_id=? AND user_id=? AND conversation_id=? ORDER BY created_at",
                (rs, rowNum) -> view(identity, map(rs)), identity.tenantId(), identity.userId(), conversationId);
    }

    public List<JobView> archive(ChatIdentity identity, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        return jdbc.query("SELECT * FROM image_job WHERE tenant_id=? AND user_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT " + safeLimit,
                (rs, rowNum) -> view(identity, map(rs)), identity.tenantId(), identity.userId());
    }

    private JobView view(ChatIdentity identity, String jobId) {
        return view(identity, requireOwnedJob(identity, jobId));
    }

    private JobRecord requireOwnedJob(ChatIdentity identity, String jobId) {
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId 不能为空");
        }
        List<JobRecord> rows = jdbc.query("SELECT * FROM image_job WHERE id=? AND tenant_id=? AND user_id=?",
                (rs, rowNum) -> map(rs), jobId, identity.tenantId(), identity.userId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片任务不存在");
        return rows.get(0);
    }

    private JobView view(ChatIdentity identity, JobRecord job) {
        String url = "SUCCEEDED".equals(job.status()) && job.outputObjectKey() != null
                ? storage.signedGetUrl(job.outputObjectKey()) : null;
        return new JobView(job.id(), job.status(), job.prompt(), job.mode(), job.createdAt(), job.startedAt(),
                job.completedAt(), url, job.rationale(), job.errorMessage());
    }

    private static JobRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRecord(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("conversation_id"), rs.getString("source_object_key"), rs.getString("output_object_key"),
                rs.getString("mode"), rs.getString("language"), rs.getString("prompt"), rs.getString("status"),
                rs.getString("provider"), rs.getString("model"),
                rs.getString("rationale"), rs.getString("provider_request_id"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toLocalDateTime(), nullable(rs, "started_at"), nullable(rs, "completed_at"));
    }

    private static LocalDateTime nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String inferMode(String question) {
        String text = question == null ? "" : question;
        return text.matches(".*(蒸馏|提取情绪|不要保留原图|不保留照片|抽象重构).*")
                ? "distillation" : "gathered";
    }

    public record JobRecord(String id, String tenantId, String userId, String conversationId,
                            String sourceObjectKey, String outputObjectKey, String mode, String language,
                            String prompt, String status, String provider, String model, String rationale, String providerRequestId,
                            String errorMessage,
                            /** 服务端创建队列任务的时间。 */ LocalDateTime createdAt,
                            /** Worker 成功领取任务的时间。 */ LocalDateTime startedAt,
                            /** 成功或最终失败的时间。 */ LocalDateTime completedAt) {}

    public record JobView(String jobId, String status, String prompt, String mode,
                          /** 任务进入队列的时间，也就是服务端收到并接受任务的时间。 */ LocalDateTime createdAt,
                          /** Worker 开始处理任务的时间。 */ LocalDateTime startedAt,
                          /** 任务成功或最终失败的时间；重试中的任务暂为空。 */ LocalDateTime completedAt,
                          String imageUrl,
                          String rationale, String error) {}

    public record PublishSource(String jobId, String outputObjectKey) {}
}
