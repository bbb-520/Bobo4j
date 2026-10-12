package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.security.MessageDigest;
import java.time.Duration;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationSession;

/** Auth owns payment receipts; Chat atomically commits its result and exactly one history turn. */
@Service
public class InternalCompletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ModelCallClient models;
    private final ConversationPersistenceService conversations;
    private final InternalServiceClient media;
    private final JsonMapper json = JsonMapper.builder().build();

    public InternalCompletionService(
            JdbcTemplate j,
            TransactionTemplate t,
            ModelCallClient m,
            ConversationPersistenceService c) {
        this(j, t, m, c, null);
    }

    @Autowired
    public InternalCompletionService(
            JdbcTemplate j,
            TransactionTemplate t,
            ModelCallClient m,
            ConversationPersistenceService c,
            InternalServiceClient media) {
        this.jdbc = j;
        this.tx = t;
        this.models = m;
        this.conversations = c;
        this.media = media;
    }

    // 1. 同步内部生成：使用与流式入口相同的请求校验和持久回执流程。
    public Completion complete(ChatIdentity identity, CompletionRequest request) {
        return completeCore(identity, request, null);
    }

    // 1.1. 流式内部生成：在返回最终结果的同时转发模型生成事件。
    public Completion completeStreaming(
            ChatIdentity identity,
            CompletionRequest request,
            java.util.function.Consumer<GenerationEvent> events) {
        if (request == null || !"CHAT".equals(request.mode())) {
            throw new IllegalArgumentException("流式正文仅支持普通 CHAT，文档答案必须先验证");
        }

        return completeCore(identity, request, events);
    }

    // 2. 生成核心：校验幂等请求，组织历史和模型调用，并保存可恢复结果。
    private Completion completeCore(
            ChatIdentity identity,
            CompletionRequest request,
            java.util.function.Consumer<GenerationEvent> events) {
        if (identity == null
                || !identity.authenticated()
                || request == null
                || request.callId() == null
                || !request.callId().matches("[A-Za-z0-9_.:-]{1,128}")
                || !Set.of("CHAT", "DOCUMENT_QA", "SUMMARY", "EVALUATION", "DECISION")
                        .contains(request.mode())
                || ((request.user() == null || request.user().isBlank())
                        && (request.attachments() == null || request.attachments().isEmpty()))) {
            throw new IllegalArgumentException("内部生成请求无效");
        }

        List<ChatAttachmentRequest> attachments = attachments(request);
        String question =
                request.user() == null || request.user().isBlank()
                        ? "请描述这些图片。"
                        : request.user();
        String hash = hash(request);
        var existing =
                jdbc.queryForList(
                        "SELECT * FROM chat_completion WHERE call_id=?", request.callId());

        if (!existing.isEmpty()) {
            check(existing.getFirst(), identity, hash);
        }

        if (!existing.isEmpty() && existing.getFirst().get("receipt") != null) {
            var cached =
                    ModelCallClient.completionFromReceipt(
                            (String) existing.getFirst().get("receipt"));

            if (events != null) {
                events.accept(ModelCallClient.committedEvent(cached));
            }

            return cached;
        }

        ConversationSession session =
                "CHAT".equals(request.mode()) && request.conversationId() != null
                        ? conversations.openOrCreate(request.conversationId(), identity)
                        : null;
        PreparedCall prepared =
                tx.execute(
                        s -> {
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE",
                                            request.callId());

                            if (!rows.isEmpty()) {
                                check(rows.getFirst(), identity, hash);
                                return prepared(rows.getFirst());
                            }

                            String history =
                                    session == null ? "" : conversations.committedHistory(session);
                            String user =
                                    history.isBlank()
                                            ? question
                                            : "【已提交的会话历史，仅用于理解指代】\n"
                                                    + history
                                                    + "\n【当前问题】\n"
                                                    + question;

                            jdbc.update(
                                    "INSERT INTO chat_completion(call_id,user_id,tenant_id,parameter_hash,model_user,status) VALUES (?,?,?,?,?,?)",
                                    request.callId(),
                                    identity.userId(),
                                    identity.tenantId(),
                                    hash,
                                    user,
                                    attachments.isEmpty() ? "DISPATCHED" : "VALIDATING");

                            return new PreparedCall(
                                    user, attachments.isEmpty() ? "DISPATCHED" : "VALIDATING", null);
                        });

        if (prepared.receipt() != null) {
            var cached = ModelCallClient.completionFromReceipt(prepared.receipt());
            if (events != null) {
                events.accept(ModelCallClient.committedEvent(cached));
            }
            return cached;
        }

        Completion recovered =
                !attachments.isEmpty() && "DISPATCHED".equals(prepared.status())
                        ? models.recoverCompletion(identity, request.callId()).orElse(null)
                        : null;
        List<VisionImage> images;

        try {
            images = recovered == null ? resolveImages(identity, attachments) : List.of();
        } catch (ImageAttachmentUnavailable error) {
            boolean rejected = tx.execute(s -> {
                var row = jdbc.queryForList(
                                "SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE", request.callId())
                        .getFirst();
                check(row, identity, hash);
                if ("VALIDATING".equals(row.get("status"))) {
                    jdbc.update("UPDATE chat_completion SET status='ASSET_REJECTED',updated_at=CURRENT_TIMESTAMP WHERE call_id=? AND status='VALIDATING'",
                            request.callId());
                    return true;
                }
                return "ASSET_REJECTED".equals(row.get("status"));
            });
            if (!rejected) {
                throw new ModelCallClient.ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
            }
            throw error;
        }

        if (!attachments.isEmpty() && recovered == null) {
            tx.executeWithoutResult(s -> {
                var row = jdbc.queryForList(
                                "SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE", request.callId())
                        .getFirst();
                check(row, identity, hash);
                if ("ASSET_REJECTED".equals(row.get("status"))) {
                    throw new ImageAttachmentUnavailable();
                }
                if ("VALIDATING".equals(row.get("status"))) {
                    jdbc.update("UPDATE chat_completion SET status='DISPATCHED',updated_at=CURRENT_TIMESTAMP WHERE call_id=? AND status='VALIDATING'",
                            request.callId());
                } else if (!"DISPATCHED".equals(prepared.status())) {
                    throw new ModelCallClient.ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
                }
            });
        }

        String modelUser = prepared.modelUser();
        // Re-entering after a crash is safe: Auth returns the same receipt or rejects unknown
        // dispatch.
        java.util.function.Consumer<GenerationEvent> modelEvents =
                event -> {
                    if (events != null && !event.type().equals("answer_committed")) {
                        events.accept(event);
                    }
                };
        Completion result;

        if (recovered != null) {
            result = recovered;
        } else if (!images.isEmpty()) {
            result = models.completeVision(
                    identity,
                    request.callId(),
                    request.system() == null ? "" : request.system(),
                    modelUser,
                    images,
                    request.tokenBudget(),
                    modelEvents);
        } else if (events == null) {
            result = models.complete(
                    identity,
                    request.callId(),
                    request.mode(),
                    request.system() == null ? "" : request.system(),
                    modelUser,
                    !Set.of("EVALUATION", "DECISION").contains(request.mode()),
                    request.tokenBudget());
        } else {
            result = models.completeStreaming(
                    identity,
                    request.callId(),
                    request.mode(),
                    request.system() == null ? "" : request.system(),
                    modelUser,
                    true,
                    request.tokenBudget(),
                    modelEvents);
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("已停止");
        }

        Completion committed =
                tx.execute(
                        s -> {
                            var row =
                                    jdbc.queryForList(
                                                    "SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE",
                                                    request.callId())
                                            .getFirst();

                            check(row, identity, hash);

                            if (row.get("receipt") != null) {
                                return ModelCallClient.completionFromReceipt(
                                        (String) row.get("receipt"));
                            }

                            if (session != null
                                    && !Boolean.TRUE.equals(row.get("history_committed"))) {
                                conversations.appendTurn(
                                        session, question, result.text(), true);
                            }

                            jdbc.update(
                                    "UPDATE chat_completion SET status='SUCCEEDED',receipt=?,history_committed=?,updated_at=CURRENT_TIMESTAMP WHERE call_id=?",
                                    json.writeValueAsString(result),
                                    session != null,
                                    request.callId());

                            return result;
                        });

        if (events != null) {
            events.accept(ModelCallClient.committedEvent(committed));
        }

        return committed;
    }

    private record PreparedCall(String modelUser, String status, String receipt) {}

    private static PreparedCall prepared(Map<String, Object> row) {
        if ("ASSET_REJECTED".equals(row.get("status"))) {
            throw new ImageAttachmentUnavailable();
        }
        return new PreparedCall(
                (String) row.get("model_user"), (String) row.get("status"), (String) row.get("receipt"));
    }

    private String hash(CompletionRequest r) {
        try {
            List<Object> parameters =
                    new ArrayList<>(
                            List.of(
                                    r.callId(),
                                    r.mode(),
                                    r.system() == null ? "" : r.system(),
                                    r.user() == null ? "" : r.user(),
                                    r.conversationId() == null ? "" : r.conversationId()));

            // Preserve the exact pre-attachment JSON shape for durable text calls.
            if (r.attachments() != null && !r.attachments().isEmpty()) {
                parameters.add(r.attachments());
            }

            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(parameters)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ChatAttachmentRequest> attachments(CompletionRequest request) {
        List<ChatAttachmentRequest> images = request.attachments();

        if (images == null || images.isEmpty()) {
            return List.of();
        }

        if (!"CHAT".equals(request.mode()) || images.size() > 3) {
            throw new IllegalArgumentException("普通聊天最多支持 3 张图片");
        }

        var ids = new HashSet<String>();

        for (ChatAttachmentRequest image : images) {
            if (image == null
                    || image.getAssetId() == null
                    || !image.getAssetId().matches("[A-Za-z0-9_.:@-]{1,128}")
                    || !ids.add(image.getAssetId())
                    || (image.getMimeType() != null
                            && !image.getMimeType().toLowerCase(Locale.ROOT).startsWith("image/"))
                    || (image.getFileSize() != null
                            && (image.getFileSize() <= 0 || image.getFileSize() > 20L * 1024 * 1024))) {
                throw new IllegalArgumentException("图片资产引用无效");
            }
        }

        return List.copyOf(images);
    }

    private List<VisionImage> resolveImages(
            ChatIdentity identity, List<ChatAttachmentRequest> attachments) {
        List<VisionImage> images = new ArrayList<>();

        for (ChatAttachmentRequest attachment : attachments) {
            InternalServiceClient.MediaAsset asset;

            try {
                asset =
                        media.mediaAsset(
                                        attachment.getAssetId(), identity.userId(), identity.tenantId())
                                .block(Duration.ofSeconds(6));
            } catch (org.springframework.web.server.ResponseStatusException error) {
                if (Set.of(400, 403, 404, 409, 410).contains(error.getStatusCode().value())) {
                    throw new ImageAttachmentUnavailable();
                }

                throw error;
            }

            if (asset == null
                    || !attachment.getAssetId().equals(asset.assetId())
                    || asset.mimeType() == null
                    || !asset.mimeType().toLowerCase(Locale.ROOT).startsWith("image/")
                    || asset.fileSize() <= 0
                    || asset.fileSize() > 20L * 1024 * 1024
                    || asset.downloadUrl() == null
                    || asset.downloadUrl().isBlank()) {
                throw new ImageAttachmentUnavailable();
            }

            images.add(new VisionImage(asset.assetId(), asset.downloadUrl()));
        }

        return List.copyOf(images);
    }

    public static final class ImageAttachmentUnavailable extends IllegalArgumentException {
        public ImageAttachmentUnavailable() {
            super("图片附件不可用或已过期，请重新上传。");
        }
    }

    // 3. 请求边界：限制内部生成参数，避免非法请求进入付费模型调用。
    private static void check(Map<String, Object> row, ChatIdentity i, String hash) {
        if (!Objects.equals(row.get("user_id"), i.userId())
                || !Objects.equals(row.get("tenant_id"), i.tenantId())
                || !Objects.equals(row.get("parameter_hash"), hash)) {
            throw new IllegalStateException("内部调用身份或幂等参数冲突");
        }
    }
}
