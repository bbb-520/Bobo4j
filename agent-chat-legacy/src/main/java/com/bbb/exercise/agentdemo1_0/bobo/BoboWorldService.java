package com.bbb.exercise.agentdemo1_0.bobo;

import com.aliyun.oss.model.ObjectMetadata;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.config.OssProperties;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.image.ImageJobService;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 发布授权清单、用户作品管理和 Bobo's World 公共读取。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BoboWorldService {
    private static final int MAX_CAPTION_CODE_POINTS = 500;
    private static final int MAX_LIMIT = 48;
    private static final java.time.Duration PUBLIC_URL_TTL = java.time.Duration.ofMinutes(5);
    private static final String WORLD_THUMBNAIL_PROCESS = "image/resize,m_lfit,w_640,h_640/quality,q_85";

    private final JdbcTemplate jdbc;
    private final ImageJobService imageJobs;
    private final AuthService authService;
    private final OssStorageService storage;
    private final OssProperties ossProperties;

    @Transactional
    public PublishResponse publish(ChatIdentity identity, PublishRequest request) {
        if (identity == null || !identity.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (request == null || request.jobId() == null || request.jobId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId 不能为空");
        }
        String caption = normalizeCaption(request.caption());
        boolean anonymous = request.anonymous() == null || request.anonymous();
        ImageJobService.PublishSource source = imageJobs.requirePublishableOutput(identity, request.jobId().trim());

        ItemRow existing = findBySource(identity, source.jobId());
        if (existing != null) {
            if ("DELETED".equals(existing.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该生成结果已删除，不能重复发布");
            }
            log.info("Bobo World publish repeated; returning existing item itemId={} jobId={}", existing.id(), source.jobId());
            return publishResponse(existing);
        }

        verifyOutputObject(source.jobId(), source.outputObjectKey());
        String itemId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String displayName = authService.username(identity);
        try {
            jdbc.update("INSERT INTO bobo_world_item(id,tenant_id,user_id,source_job_id,image_object_key,caption,anonymous,visibility,status,display_name_snapshot,version,created_at,updated_at,deleted_at,cleanup_pending) "
                            + "VALUES (?,?,?,?,?,?,?,'PUBLIC','ACTIVE',?,1,?,?,NULL,0)",
                    itemId, identity.tenantId(), identity.userId(), source.jobId(), source.outputObjectKey(),
                    caption, anonymous ? 1 : 0, displayName, now, now);
        } catch (DuplicateKeyException duplicate) {
            // A fast double click can race the pre-insert lookup; the unique key is the final idempotency guard.
            ItemRow raced = findBySource(identity, source.jobId());
            if (raced == null) throw duplicate;
            log.info("Bobo World publish raced; returning existing item itemId={} jobId={}", raced.id(), source.jobId());
            return publishResponse(raced);
        }
        ItemRow created = requireOwned(identity, itemId);
        log.info("Bobo World item published itemId={} owner={} jobId={} anonymous={}",
                itemId, identity.userId(), source.jobId(), anonymous);
        return publishResponse(created);
    }

    public WorldPage world(int requestedLimit, String cursor) {
        int limit = clampLimit(requestedLimit);
        Cursor after = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("SELECT b.*,j.prompt AS source_prompt,u.username AS current_username FROM bobo_world_item b "
                + "LEFT JOIN image_job j ON j.id=b.source_job_id "
                + "LEFT JOIN app_user u ON b.user_id=CONCAT('user:',u.id) "
                + "WHERE b.status='ACTIVE' AND b.visibility='PUBLIC'");
        List<Object> args = new ArrayList<>();
        if (after != null) {
            sql.append(" AND (b.created_at < ? OR (b.created_at = ? AND b.id < ?))");
            args.add(after.createdAt());
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql.append(" ORDER BY b.created_at DESC,b.id DESC LIMIT ?");
        args.add(limit + 1);
        List<ItemRow> rows = jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        List<WorldItem> items = rows.stream().map(this::worldItem).toList();
        String next = more && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;
        return new WorldPage(items, next);
    }

    public MinePage mine(ChatIdentity identity, int requestedLimit, String cursor) {
        int limit = clampLimit(requestedLimit);
        Cursor after = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("SELECT b.*,j.prompt AS source_prompt,u.username AS current_username FROM bobo_world_item b "
                + "LEFT JOIN image_job j ON j.id=b.source_job_id "
                + "LEFT JOIN app_user u ON b.user_id=CONCAT('user:',u.id) "
                + "WHERE b.tenant_id=? AND b.user_id=? AND b.status<>'DELETED'");
        List<Object> args = new ArrayList<>();
        args.add(identity.tenantId());
        args.add(identity.userId());
        if (after != null) {
            sql.append(" AND (b.created_at < ? OR (b.created_at = ? AND b.id < ?))");
            args.add(after.createdAt());
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql.append(" ORDER BY b.created_at DESC,b.id DESC LIMIT ?");
        args.add(limit + 1);
        List<ItemRow> rows = jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        List<MineItem> items = rows.stream().map(this::mineItem).toList();
        String next = more && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;
        Long publicCount = jdbc.queryForObject("SELECT COUNT(*) FROM bobo_world_item WHERE tenant_id=? AND user_id=? AND status='ACTIVE' AND visibility='PUBLIC'",
                Long.class, identity.tenantId(), identity.userId());
        return new MinePage(items, next, publicCount == null ? 0 : publicCount);
    }

    public MineItem get(ChatIdentity identity, String itemId) {
        List<ItemRow> publicRows = jdbc.query("SELECT b.*,j.prompt AS source_prompt,u.username AS current_username FROM bobo_world_item b "
                        + "LEFT JOIN image_job j ON j.id=b.source_job_id "
                        + "LEFT JOIN app_user u ON b.user_id=CONCAT('user:',u.id) "
                        + "WHERE b.id=? AND b.status='ACTIVE' AND b.visibility='PUBLIC'",
                (rs, n) -> map(rs), itemId);
        if (!publicRows.isEmpty()) return mineItem(publicRows.get(0));
        if (identity.authenticated()) return mineItem(requireOwned(identity, itemId));
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
    }

    @Transactional
    public MineItem patch(ChatIdentity identity, String itemId, PatchRequest patch) {
        if (identity == null || !identity.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (patch == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        ItemRow row = requireOwned(identity, itemId);
        if ("DELETED".equals(row.status())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
        if (patch.version() != row.version()) {
            log.info("Bobo World edit conflict itemId={} owner={} expectedVersion={} actualVersion={}",
                    itemId, identity.userId(), patch.version(), row.version());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "作品已被修改，请刷新后重试");
        }
        String caption = patch.captionProvided() ? normalizeCaption(patch.caption()) : row.caption();
        boolean anonymous = patch.anonymous() == null ? row.anonymous() : patch.anonymous();
        String visibility = patch.visibility() == null ? row.visibility() : patch.visibility();
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility 只能是 PUBLIC 或 PRIVATE");
        }
        int updated = jdbc.update("UPDATE bobo_world_item SET caption=?,anonymous=?,visibility=?,version=version+1,updated_at=? "
                        + "WHERE id=? AND tenant_id=? AND user_id=? AND status<>'DELETED' AND version=?",
                caption, anonymous ? 1 : 0, visibility, LocalDateTime.now(), itemId,
                identity.tenantId(), identity.userId(), patch.version());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "作品已被修改，请刷新后重试");
        log.info("Bobo World item edited itemId={} owner={} visibility={} anonymous={}",
                itemId, identity.userId(), visibility, anonymous);
        return mineItem(requireOwned(identity, itemId));
    }

    @Transactional
    public void delete(ChatIdentity identity, String itemId) {
        if (identity == null || !identity.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        ItemRow row = requireOwned(identity, itemId);
        if ("DELETED".equals(row.status())) return;
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE bobo_world_item SET status='DELETED',deleted_at=?,updated_at=?,cleanup_pending=1 "
                        + "WHERE id=? AND tenant_id=? AND user_id=? AND status<>'DELETED'",
                now, now, itemId, identity.tenantId(), identity.userId());
        log.info("Bobo World item deleted and queued for OSS cleanup itemId={} owner={}", itemId, identity.userId());
    }

    private void verifyOutputObject(String jobId, String objectKey) {
        try {
            ObjectMetadata metadata = storage.metadata(objectKey);
            long size = metadata.getContentLength();
            String contentType = metadata.getContentType();
            if (size <= 0 || size > ossProperties.getMaxObjectBytes()
                    || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "生成结果不是可发布的图片");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Bobo World publish could not verify generated image jobId={}", jobId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "生成图片暂不可用，请稍后重试");
        }
    }

    private WorldItem worldItem(ItemRow row) {
        String caption = normalizeCaption(row.caption());
        String senderName = row.anonymous() ? null : firstNonBlank(row.displayNameSnapshot(), row.currentUsername());
        return new WorldItem(row.id(), signedUrl(row.imageObjectKey()),
                signedUrl(row.imageObjectKey(), WORLD_THUMBNAIL_PROCESS), caption != null, caption,
                senderName, row.anonymous(), iso(row.createdAt()));
    }

    private MineItem mineItem(ItemRow row) {
        return new MineItem(row.id(), row.status(), row.visibility(), row.caption(), row.prompt(), row.anonymous(), row.version(),
                "DELETED".equals(row.status()) ? null : signedUrl(row.imageObjectKey()), iso(row.createdAt()), iso(row.updatedAt()));
    }

    private PublishResponse publishResponse(ItemRow row) {
        return new PublishResponse(row.id(), row.status(), row.caption(), row.anonymous(), row.visibility(), row.version(),
                "DELETED".equals(row.status()) ? null : signedUrl(row.imageObjectKey()), iso(row.createdAt()));
    }

    private String signedUrl(String objectKey) {
        return signedUrl(objectKey, null);
    }

    private String signedUrl(String objectKey, String process) {
        try {
            return storage.signedGetUrl(objectKey, process, PUBLIC_URL_TTL);
        } catch (Exception e) {
            log.warn("Bobo World could not sign image URL", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片暂时无法读取，请稍后重试");
        }
    }

    private ItemRow requireOwned(ChatIdentity identity, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemId 不能为空");
        }
        List<ItemRow> rows = jdbc.query("SELECT b.*,j.prompt AS source_prompt,u.username AS current_username FROM bobo_world_item b "
                        + "LEFT JOIN image_job j ON j.id=b.source_job_id "
                        + "LEFT JOIN app_user u ON b.user_id=CONCAT('user:',u.id) "
                        + "WHERE b.id=? AND b.tenant_id=? AND b.user_id=?",
                (rs, n) -> map(rs), itemId, identity.tenantId(), identity.userId());
        if (rows.isEmpty()) {
            log.warn("Bobo World ownership check rejected itemId={} actor={}", itemId, identity.userId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
        }
        return rows.get(0);
    }

    private ItemRow findBySource(ChatIdentity identity, String sourceJobId) {
        List<ItemRow> rows = jdbc.query("SELECT b.*,j.prompt AS source_prompt,u.username AS current_username FROM bobo_world_item b "
                        + "LEFT JOIN image_job j ON j.id=b.source_job_id "
                        + "LEFT JOIN app_user u ON b.user_id=CONCAT('user:',u.id) "
                        + "WHERE b.tenant_id=? AND b.user_id=? AND b.source_job_id=?",
                (rs, n) -> map(rs), identity.tenantId(), identity.userId(), sourceJobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ItemRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("source_job_id"), rs.getString("image_object_key"), rs.getString("caption"),
                rs.getBoolean("anonymous"), rs.getString("visibility"), rs.getString("status"),
                rs.getString("display_name_snapshot"), rs.getInt("version"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
                nullable(rs, "deleted_at"), rs.getInt("cleanup_pending") == 1, rs.getString("current_username"),
                rs.getString("source_prompt"));
    }

    private static LocalDateTime nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String normalizeCaption(String caption) {
        if (caption == null || caption.isBlank()) return null;
        String value = caption.strip();
        if (value.codePointCount(0, value.length()) > MAX_CAPTION_CODE_POINTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文案最多 500 个 Unicode 字符");
        }
        return value;
    }

    private static int clampLimit(int requested) { return Math.max(1, Math.min(MAX_LIMIT, requested)); }

    private static Instant iso(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String encodeCursor(ItemRow row) {
        String raw = row.createdAt() + "\n" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\n", 2);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException();
            LocalDateTime createdAt = LocalDateTime.parse(parts[0]);
            UUID.fromString(parts[1]);
            return new Cursor(createdAt, parts[1]);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页游标无效");
        }
    }

    private record Cursor(LocalDateTime createdAt, String id) {}
    private record ItemRow(String id, String tenantId, String userId, String sourceJobId, String imageObjectKey,
                          String caption, boolean anonymous, String visibility, String status, String displayNameSnapshot,
                          int version, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt,
                          boolean cleanupPending, String currentUsername, String prompt) {}

    public record PublishRequest(String jobId, String caption, Boolean anonymous) {}
    public record PatchRequest(boolean captionProvided, String caption, Boolean anonymous, String visibility, int version) {}
    public record PublishResponse(String itemId, String status, String caption, boolean anonymous, String visibility,
                                 int version, String imageUrl, Instant createdAt) {}
    public record WorldItem(String itemId, String imageUrl, String thumbnailUrl, boolean hasCaption, String caption,
                            String senderName, boolean anonymous, Instant createdAt) {}
    public record MineItem(String itemId, String status, String visibility, String caption, String prompt, boolean anonymous,
                          int version, String imageUrl, Instant createdAt, Instant updatedAt) {}
    public record WorldPage(List<WorldItem> items, String nextCursor) {}
    public record MinePage(List<MineItem> items, String nextCursor, long publicCount) {}
}
