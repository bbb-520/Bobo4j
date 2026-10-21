package com.bbb.exercise.agentdemo.contentservice.bobo;

import com.aliyun.oss.model.ObjectMetadata;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final String WORLD_THUMBNAIL_PROCESS =
            "image/resize,m_lfit,w_640,h_640/quality,q_85";

    private final JdbcTemplate jdbc;
    private final InternalServiceClient mediaClient;
    private final OssStorageService storage;
    private final OssProperties ossProperties;

    // 1. 作品发布：来源必须是当前用户已成功的媒体任务。
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
        // 1.1. 来源确认：向 Media 查询真实成功回执，客户端不能直接指定任意对象地址。
        InternalServiceClient.PublishSource source =
                mediaClient
                        .mediaPublishSource(
                                request.jobId().trim(), identity.userId(), identity.tenantId())
                        .block(java.time.Duration.ofSeconds(5));

        if (source == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体任务服务未返回结果");
        }

        // 1.2. 发布幂等：已有活跃作品直接复用，已删除来源不允许再次发布。
        ItemRow existing = findBySource(identity, source.jobId());

        if (existing != null) {
            if ("DELETED".equals(existing.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该生成结果已删除，不能重复发布");
            }

            log.info(
                    "Bobo World publish repeated; returning existing item itemId={} jobId={}",
                    existing.id(),
                    source.jobId());

            return publishResponse(existing);
        }

        // 1.3. 对象确认：检查结果对象存在及元数据，再保存作品授权和展示快照。
        verifyOutputObject(source.jobId(), source.outputObjectKey());
        String itemId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String displayName = identity.userId();

        try {
            jdbc.update(
                    "INSERT INTO bobo_world_item(id,tenant_id,user_id,source_job_id,image_object_key,caption,anonymous,visibility,status,display_name_snapshot,version,created_at,updated_at,deleted_at,cleanup_pending,source_prompt) "
                            + "VALUES (?,?,?,?,?,?,?,'PUBLIC','ACTIVE',?,1,?,?,NULL,0,?)",
                    itemId,
                    identity.tenantId(),
                    identity.userId(),
                    source.jobId(),
                    source.outputObjectKey(),
                    caption,
                    anonymous ? 1 : 0,
                    displayName,
                    now,
                    now,
                    source.prompt());
        } catch (DuplicateKeyException duplicate) {
            // A fast double click can race the pre-insert lookup; the unique key is the final
            // idempotency guard.
            ItemRow raced = findBySource(identity, source.jobId());

            if (raced == null) {
                throw duplicate;
            }

            log.info(
                    "Bobo World publish raced; returning existing item itemId={} jobId={}",
                    raced.id(),
                    source.jobId());

            return publishResponse(raced);
        }

        ItemRow created = requireOwned(identity, itemId);

        log.info(
                "Bobo World item published itemId={} owner={} jobId={} anonymous={}",
                itemId,
                identity.userId(),
                source.jobId(),
                anonymous);

        return publishResponse(created);
    }

    // 2. 作品浏览：只选择 ACTIVE 且 PUBLIC 的作品，按时间和 ID 组合游标分页。
    public WorldPage world(int requestedLimit, String cursor) {
        int limit = clampLimit(requestedLimit);
        Cursor after = decodeCursor(cursor);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT b.* FROM bobo_world_item b "
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

        if (more) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }

        List<WorldItem> items = rows.stream().map(this::worldItem).toList();
        String next = more && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;

        return new WorldPage(items, next);
    }

    // 2.1. 我的作品：限定当前用户和租户，同时返回公开作品数量。
    public MinePage mine(ChatIdentity identity, int requestedLimit, String cursor) {
        int limit = clampLimit(requestedLimit);
        Cursor after = decodeCursor(cursor);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT b.* FROM bobo_world_item b "
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

        if (more) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }

        List<MineItem> items = rows.stream().map(this::mineItem).toList();
        String next = more && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;
        Long publicCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bobo_world_item WHERE tenant_id=? AND user_id=? AND status='ACTIVE' AND visibility='PUBLIC'",
                        Long.class,
                        identity.tenantId(),
                        identity.userId());

        return new MinePage(items, next, publicCount == null ? 0 : publicCount);
    }

    // 2.2. 详情可见性：公开作品可走公共读取分支，其他作品需要核对所有权。
    public ItemDetail get(ChatIdentity identity, String itemId) {
        List<ItemRow> publicRows =
                jdbc.query(
                        "SELECT b.* FROM bobo_world_item b "
                                + "WHERE b.id=? AND b.status='ACTIVE' AND b.visibility='PUBLIC'",
                        (rs, n) -> map(rs),
                        itemId);

        if (!publicRows.isEmpty()) {
            return itemDetail(publicRows.get(0));
        }

        if (identity != null && identity.authenticated()) {
            return itemDetail(requireOwned(identity, itemId));
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
    }

    // 3. 作品编辑：检查 owner 和版本，条件更新成功后递增版本。
    @Transactional
    public MineItem patch(ChatIdentity identity, String itemId, PatchRequest patch) {
        if (identity == null || !identity.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }

        if (patch == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }

        ItemRow row = requireOwned(identity, itemId);

        if ("DELETED".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
        }

        if (patch.version() != row.version()) {
            log.info(
                    "Bobo World edit conflict itemId={} owner={} expectedVersion={} actualVersion={}",
                    itemId,
                    identity.userId(),
                    patch.version(),
                    row.version());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "作品已被修改，请刷新后重试");
        }

        String caption =
                patch.captionProvided() ? normalizeCaption(patch.caption()) : row.caption();
        boolean anonymous = patch.anonymous() == null ? row.anonymous() : patch.anonymous();
        String visibility = patch.visibility() == null ? row.visibility() : patch.visibility();

        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "visibility 只能是 PUBLIC 或 PRIVATE");
        }

        int updated =
                jdbc.update(
                        "UPDATE bobo_world_item SET caption=?,anonymous=?,visibility=?,version=version+1,updated_at=? "
                                + "WHERE id=? AND tenant_id=? AND user_id=? AND status<>'DELETED' AND version=?",
                        caption,
                        anonymous ? 1 : 0,
                        visibility,
                        LocalDateTime.now(),
                        itemId,
                        identity.tenantId(),
                        identity.userId(),
                        patch.version());

        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "作品已被修改，请刷新后重试");
        }

        log.info(
                "Bobo World item edited itemId={} owner={} visibility={} anonymous={}",
                itemId,
                identity.userId(),
                visibility,
                anonymous);

        return mineItem(requireOwned(identity, itemId));
    }

    // 4. 作品删除：撤下作品只改变可见性状态，不触碰 Media 所有的生成资产。
    @Transactional
    public void delete(ChatIdentity identity, String itemId) {
        if (identity == null || !identity.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }

        ItemRow row = requireOwned(identity, itemId);

        if ("DELETED".equals(row.status())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        jdbc.update(
                "UPDATE bobo_world_item SET status='DELETED',deleted_at=?,updated_at=? "
                        + "WHERE id=? AND tenant_id=? AND user_id=? AND status<>'DELETED'",
                now,
                now,
                itemId,
                identity.tenantId(),
                identity.userId());
        log.info(
                "Bobo World item withdrawn itemId={} owner={}; generated asset stays with Media",
                itemId,
                identity.userId());
    }

    // 4.1. 存量兼容：旧版本把作品撤下当作"删除独占副本"，把共享的生成对象排入清理队列。
    /**
     * Retires cleanup intents written by previous versions.
     *
     * <p>The object in {@code image_object_key} is always the Media job's own output: Content only
     * references it. Deleting it here would destroy a generation the user still owns, so legacy
     * pending rows are drained without calling the object store. Physical deletion stays with the
     * asset owner (Media), and any independent work copy needs a separate design.
     */
    @Scheduled(
            fixedDelayString = "${app.bobo.cleanup-interval:60000}",
            initialDelayString = "${app.bobo.cleanup-initial-delay:30000}")
    public void retireLegacyCleanupRequests() {
        List<CleanupCandidate> candidates =
                jdbc.query(
                        "SELECT id,image_object_key FROM bobo_world_item "
                                + "WHERE status='DELETED' AND cleanup_pending=1 "
                                + "ORDER BY updated_at LIMIT 50",
                        (rs, rowNum) ->
                                new CleanupCandidate(
                                        rs.getString("id"), rs.getString("image_object_key")));

        for (CleanupCandidate candidate : candidates) {
            int updated =
                    jdbc.update(
                            "UPDATE bobo_world_item SET cleanup_pending=0,updated_at=? "
                                    + "WHERE id=? AND status='DELETED' AND cleanup_pending=1",
                            LocalDateTime.now(),
                            candidate.id());

            if (updated == 1) {
                log.info(
                        "Bobo World legacy cleanup intent retired without deleting shared asset itemId={} objectKey={}",
                        candidate.id(),
                        candidate.objectKey());
            }
        }
    }

    private void verifyOutputObject(String jobId, String objectKey) {
        try {
            ObjectMetadata metadata = storage.metadata(objectKey);
            long size = metadata.getContentLength();
            String contentType = metadata.getContentType();

            if (size <= 0
                    || size > ossProperties.getMaxObjectBytes()
                    || contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
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
        String senderName =
                row.anonymous()
                        ? null
                        : firstNonBlank(row.displayNameSnapshot(), row.currentUsername());

        return new WorldItem(
                row.id(),
                signedUrl(row.imageObjectKey()),
                signedUrl(row.imageObjectKey(), WORLD_THUMBNAIL_PROCESS),
                caption != null,
                caption,
                senderName,
                row.anonymous(),
                iso(row.createdAt()));
    }

    private MineItem mineItem(ItemRow row) {
        return new MineItem(
                row.id(),
                row.status(),
                row.visibility(),
                row.caption(),
                row.prompt(),
                row.anonymous(),
                row.version(),
                "DELETED".equals(row.status()) ? null : signedUrl(row.imageObjectKey()),
                iso(row.createdAt()),
                iso(row.updatedAt()));
    }

    private ItemDetail itemDetail(ItemRow row) {
        return new ItemDetail(
                row.id(),
                row.status(),
                row.visibility(),
                row.caption(),
                row.anonymous(),
                row.version(),
                "DELETED".equals(row.status()) ? null : signedUrl(row.imageObjectKey()),
                iso(row.createdAt()),
                iso(row.updatedAt()));
    }

    private PublishResponse publishResponse(ItemRow row) {
        return new PublishResponse(
                row.id(),
                row.status(),
                row.caption(),
                row.anonymous(),
                row.visibility(),
                row.version(),
                "DELETED".equals(row.status()) ? null : signedUrl(row.imageObjectKey()),
                iso(row.createdAt()));
    }

    // 5. 展示地址：按需生成限时签名 URL，数据库保留的是对象位置。
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

    // 5.1. 管理归属：编辑、删除和私有读取均以当前用户为边界。
    private ItemRow requireOwned(ChatIdentity identity, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemId 不能为空");
        }

        List<ItemRow> rows =
                jdbc.query(
                        "SELECT b.* FROM bobo_world_item b "
                                + "WHERE b.id=? AND b.tenant_id=? AND b.user_id=?",
                        (rs, n) -> map(rs),
                        itemId,
                        identity.tenantId(),
                        identity.userId());

        if (rows.isEmpty()) {
            log.warn(
                    "Bobo World ownership check rejected itemId={} actor={}",
                    itemId,
                    identity.userId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
        }

        return rows.get(0);
    }

    private ItemRow findBySource(ChatIdentity identity, String sourceJobId) {
        List<ItemRow> rows =
                jdbc.query(
                        "SELECT b.* FROM bobo_world_item b "
                                + "WHERE b.tenant_id=? AND b.user_id=? AND b.source_job_id=?",
                        (rs, n) -> map(rs),
                        identity.tenantId(),
                        identity.userId(),
                        sourceJobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ItemRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("source_job_id"),
                rs.getString("image_object_key"),
                rs.getString("caption"),
                rs.getBoolean("anonymous"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("display_name_snapshot"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                nullable(rs, "deleted_at"),
                rs.getInt("cleanup_pending") == 1,
                null,
                rs.getString("source_prompt"));
    }

    private static LocalDateTime nullable(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String normalizeCaption(String caption) {
        if (caption == null || caption.isBlank()) {
            return null;
        }

        String value = caption.strip();

        if (value.codePointCount(0, value.length()) > MAX_CAPTION_CODE_POINTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文案最多 500 个 Unicode 字符");
        }

        return value;
    }

    private static int clampLimit(int requested) {
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    private static Instant iso(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    // 6. 分页游标：同时保存时间与 ID，避免相同时间戳导致翻页遗漏。
    private static String encodeCursor(ItemRow row) {
        String raw = row.createdAt() + "\n" + row.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\n", 2);

            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException();
            }

            LocalDateTime createdAt = LocalDateTime.parse(parts[0]);

            UUID.fromString(parts[1]);

            return new Cursor(createdAt, parts[1]);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页游标无效");
        }
    }

    /** 作品列表的分页游标，通过创建时间和作品 ID 确定稳定排序位置。 */
    private record Cursor(LocalDateTime createdAt, String id) {}

    /** 待清理作品的定位信息，将作品记录与需删除的对象存储文件关联。 */
    private record CleanupCandidate(String id, String objectKey) {}

    /** 作品的内部数据库快照，包含归属、发布状态、版本及源任务信息。 */
    private record ItemRow(
            String id,
            String tenantId,
            String userId,
            String sourceJobId,
            String imageObjectKey,
            String caption,
            boolean anonymous,
            String visibility,
            String status,
            String displayNameSnapshot,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt,
            boolean cleanupPending,
            String currentUsername,
            String prompt) {}

    /** 图片作品发布请求，指定源任务、配文和匿名展示选项。 */
    public record PublishRequest(String jobId, String caption, Boolean anonymous) {}

    /** 作品编辑请求，区分未提供配文与清空配文，并携带并发更新版本。 */
    public record PatchRequest(
            boolean captionProvided,
            String caption,
            Boolean anonymous,
            String visibility,
            int version) {}

    /** 作品发布结果，返回作品标识、展示属性、版本和图片访问地址。 */
    public record PublishResponse(
            String itemId,
            String status,
            String caption,
            boolean anonymous,
            String visibility,
            int version,
            String imageUrl,
            Instant createdAt) {}

    /** 公开作品列表项，仅提供浏览所需的图片、配文和发布者展示信息。 */
    public record WorldItem(
            String itemId,
            String imageUrl,
            String thumbnailUrl,
            boolean hasCaption,
            String caption,
            String senderName,
            boolean anonymous,
            Instant createdAt) {}

    /** 个人作品列表项，提供管理所需的状态、提示词、版本和更新时间。 */
    public record MineItem(
            String itemId,
            String status,
            String visibility,
            String caption,
            String prompt,
            boolean anonymous,
            int version,
            String imageUrl,
            Instant createdAt,
            Instant updatedAt) {}

    /** 作品详情视图，返回展示属性、状态、版本及图片访问地址。 */
    public record ItemDetail(
            String itemId,
            String status,
            String visibility,
            String caption,
            boolean anonymous,
            int version,
            String imageUrl,
            Instant createdAt,
            Instant updatedAt) {}

    /** 公开作品分页结果，包含当前页条目及下一页游标。 */
    public record WorldPage(List<WorldItem> items, String nextCursor) {}

    /** 个人作品分页结果，附带下一页游标及公开作品数量。 */
    public record MinePage(List<MineItem> items, String nextCursor, long publicCount) {}
}
