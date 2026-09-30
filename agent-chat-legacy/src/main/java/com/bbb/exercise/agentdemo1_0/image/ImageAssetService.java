package com.bbb.exercise.agentdemo1_0.image;

import com.aliyun.oss.model.ObjectMetadata;
import com.bbb.exercise.agentdemo1_0.config.OssProperties;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 图片资产生命周期：申请直传策略、校验上传完成、校验所有权。 */
@Service
@RequiredArgsConstructor
public class ImageAssetService {

    private static final Set<String> ACCEPTED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final JdbcTemplate jdbc;
    private final OssProperties ossProperties;
    private final OssStorageService storage;

    public UploadPolicyView createPolicy(ChatIdentity identity, UploadPolicyRequest request) {
        String mime = request == null ? null : request.contentType();
        mime = mime == null ? null : mime.trim().toLowerCase(Locale.ROOT);
        if (mime == null || !ACCEPTED_IMAGE_TYPES.contains(mime)) {
            throw new IllegalArgumentException("只允许上传图片");
        }
        long size = request.fileSize() == null ? 0 : request.fileSize();
        if (size <= 0 || size > ossProperties.getMaxObjectBytes()) {
            throw new IllegalArgumentException("图片大小必须在 1 字节到 "
                    + ossProperties.getMaxObjectBytes() / 1024 / 1024 + " MB 之间");
        }
        String id = UUID.randomUUID().toString();
        String objectKey = ossProperties.getSourcePrefix() + "/" + ownerHash(identity)
                + "/" + LocalDateTime.now().toLocalDate() + "/" + id + extension(mime);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO image_asset(id,tenant_id,user_id,object_key,original_name,mime_type,file_size,status,expires_at,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?)",
                id, identity.tenantId(), identity.userId(), objectKey, trimName(request.fileName()), mime, size,
                now.plusDays(1), now, now);
        OssStorageService.UploadPolicy policy = storage.createUploadPolicy(objectKey, mime);
        return new UploadPolicyView(id, objectKey, policy.uploadUrl(), policy.fields(), policy.expiresAt());
    }

    public AssetView complete(ChatIdentity identity, String assetId) {
        AssetRecord asset = requireOwned(identity, assetId);
        ObjectMetadata metadata = storage.metadata(asset.objectKey());
        long size = metadata.getContentLength();
        if (size <= 0 || size > ossProperties.getMaxObjectBytes()) {
            throw new IllegalArgumentException("OSS 中的图片大小不符合限制");
        }
        String storedMime = metadata.getContentType();
        if (storedMime != null && !asset.mimeType().equalsIgnoreCase(storedMime)) {
            throw new IllegalArgumentException("OSS 中的图片类型与上传声明不一致");
        }
        jdbc.update("UPDATE image_asset SET status='READY',file_size=?,updated_at=? WHERE id=? AND tenant_id=? AND user_id=?",
                size, LocalDateTime.now(), assetId, identity.tenantId(), identity.userId());
        return new AssetView(asset.id(), asset.objectKey(), asset.mimeType(), size);
    }

    public AssetRecord requireReady(ChatIdentity identity, String assetId) {
        AssetRecord asset = requireOwned(identity, assetId);
        if (!"READY".equals(asset.status())) throw new IllegalArgumentException("图片尚未上传完成");
        if (asset.expiresAt().isBefore(LocalDateTime.now())) throw new IllegalArgumentException("图片上传已过期");
        return asset;
    }

    public AssetRecord requireOwned(ChatIdentity identity, String assetId) {
        if (assetId == null || assetId.isBlank()) throw new IllegalArgumentException("图片资产 ID 不能为空");
        var rows = jdbc.query("SELECT id,object_key,mime_type,file_size,status,expires_at FROM image_asset WHERE id=? AND tenant_id=? AND user_id=?",
                (rs, rowNum) -> new AssetRecord(rs.getString("id"), rs.getString("object_key"),
                        rs.getString("mime_type"), rs.getLong("file_size"), rs.getString("status"),
                        rs.getTimestamp("expires_at").toLocalDateTime()),
                assetId, identity.tenantId(), identity.userId());
        if (rows.isEmpty()) throw new IllegalArgumentException("图片资产不存在或不属于当前用户");
        return rows.get(0);
    }

    private static String extension(String mime) {
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private static String trimName(String name) {
        if (name == null) return null;
        String clean = name.replaceAll("[\\r\\n\\\\/]", "_").trim();
        return clean.length() <= 255 ? clean : clean.substring(0, 255);
    }

    private static String ownerHash(ChatIdentity identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((identity.tenantId() + ":" + identity.userId()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record UploadPolicyRequest(String fileName, String contentType, Long fileSize) {}
    public record UploadPolicyView(String assetId, String objectKey, String uploadUrl,
                                   java.util.Map<String, String> fields, java.time.Instant expiresAt) {}
    public record AssetView(String assetId, String objectKey, String mimeType, long fileSize) {}
    public record AssetRecord(String id, String objectKey, String mimeType, long fileSize,
                              String status, LocalDateTime expiresAt) {}
}
