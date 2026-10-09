package com.bbb.exercise.agentdemo.runtime.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.aliyun.oss.model.OSSObjectSummary;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OSS 适配层。浏览器上传使用 PostObject 策略，服务端只保存对象键；
 * 生成结果通过私有签名 URL访问，远程结果落盘后再流式交给 OSS SDK，避免长期占用堆内存。
 */
@Service
@RequiredArgsConstructor
public class OssStorageService {

    private final OssProperties properties;
    private volatile OSS oss;

    public UploadPolicy createUploadPolicy(String objectKey, String contentType) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("OSS 对象键不能为空");
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        Instant expiresAt = Instant.now().plus(properties.getUploadPolicyTtl());
        String policyJson = "{\"expiration\":\"" + expiresAt.toString() + "\",\"conditions\":["
                + "[\"content-length-range\",0," + properties.getMaxObjectBytes() + "],"
                + "{\"key\":\"" + objectKey + "\"},"
                + "{\"Content-Type\":\"" + type + "\"}]}";
        String policy = base64(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha1Base64(policy, properties.getAccessKeySecret());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", objectKey);
        fields.put("policy", policy);
        fields.put("OSSAccessKeyId", properties.getAccessKeyId());
        fields.put("success_action_status", "200");
        fields.put("Content-Type", type);
        fields.put("Signature", signature);
        return new UploadPolicy(uploadUrl(), fields, expiresAt);
    }

    public ObjectMetadata metadata(String objectKey) {
        requireConfigured();
        return client().getObjectMetadata(properties.getBucket(), objectKey);
    }

    /** Lists direct image children only; keys remain private and are never returned to the browser. */
    public List<ArchiveImage> listArchiveImages(String archivePrefix) {
        requireConfigured();
        String normalizedPrefix = archivePrefix == null ? "" : archivePrefix.trim().replaceAll("^/+|/+$", "");
        if (normalizedPrefix.isBlank()) throw new IllegalArgumentException("OSS 相册前缀不能为空");
        String objectPrefix = normalizedPrefix + "/";
        List<ArchiveImage> images = new ArrayList<>();
        String marker = null;

        do {
            ListObjectsRequest request = new ListObjectsRequest(properties.getBucket());
            request.setPrefix(objectPrefix);
            request.setMarker(marker);
            request.setMaxKeys(1000);
            ObjectListing listing = client().listObjects(request);
            for (OSSObjectSummary object : listing.getObjectSummaries()) {
                String key = object.getKey();
                if (key == null || !key.startsWith(objectPrefix)) continue;
                String filename = key.substring(objectPrefix.length());
                // This album is a flat folder. Ignore subfolders and the console's folder marker object.
                if (filename.isBlank() || filename.length() > 180 || filename.contains("..")
                        || filename.contains("/") || filename.contains("\\") || !isSupportedImage(filename)) continue;
                images.add(new ArchiveImage(filename, object.getSize()));
            }
            if (!listing.isTruncated()) break;
            String nextMarker = listing.getNextMarker();
            if (nextMarker == null || nextMarker.isBlank() || nextMarker.equals(marker)) {
                throw new IllegalStateException("OSS 相册分页标记无效");
            }
            marker = nextMarker;
        } while (true);

        images.sort(Comparator.comparing(ArchiveImage::filename, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ArchiveImage::filename));
        return List.copyOf(images);
    }

    public String signedGetUrl(String objectKey) {
        return signedGetUrl(objectKey, null);
    }

    public String signedGetUrl(String objectKey, String process) {
        return signedGetUrl(objectKey, process, properties.getSignedUrlTtl());
    }

    public String signedGetUrl(String objectKey, String process, Duration ttl) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("OSS 对象键不能为空");
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? properties.getSignedUrlTtl() : ttl;
        Date expires = Date.from(Instant.now().plus(safeTtl));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.getBucket(), objectKey, HttpMethod.GET);
        request.setExpiration(expires);
        if (process != null && !process.isBlank()) {
            request.setProcess(process);
        }
        return client().generatePresignedUrl(request).toExternalForm();
    }

    /** Creates a short private link that asks the browser to save the result as a file. */
    public String signedDownloadUrl(String objectKey, String filename, Duration ttl) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("OSS 对象键不能为空");
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? properties.getSignedUrlTtl() : ttl;
        Date expires = Date.from(Instant.now().plus(safeTtl));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.getBucket(), objectKey, HttpMethod.GET);
        request.setExpiration(expires);
        String safeFilename = filename == null ? "bobo-image.png" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setContentDisposition("attachment; filename=\"" + safeFilename + "\"");
        request.setResponseHeaders(headers);
        return client().generatePresignedUrl(request).toExternalForm();
    }

    public void deleteObject(String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("OSS 对象键不能为空");
        client().deleteObject(properties.getBucket(), objectKey);
    }

    public void putFile(String objectKey, Path file, String contentType) throws IOException {
        requireConfigured();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(Files.size(file));
        metadata.setContentType(contentType == null ? "image/png" : contentType);
        try (InputStream input = Files.newInputStream(file)) {
            client().putObject(properties.getBucket(), objectKey, input, metadata);
        }
    }

    public void copyRemoteImageToObject(String remoteUrl, String objectKey) throws IOException, InterruptedException {
        requireConfigured();
        Path temporary = Files.createTempFile("bbb-image-output-", ".bin");
        try {
            if (remoteUrl != null && remoteUrl.startsWith("data:")) {
                int comma = remoteUrl.indexOf(',');
                if (comma < 0) throw new IOException("图片结果 data URL 无效");
                Files.write(temporary, Base64.getDecoder().decode(remoteUrl.substring(comma + 1)));
            } else {
                URI uri = validateRemoteUri(remoteUrl);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
                HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new IOException("下载图片结果失败，HTTP " + response.statusCode());
                }
                long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
                if (declared > properties.getMaxObjectBytes()) {
                    response.body().close();
                    throw new IOException("远程图片超过大小限制");
                }
                try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192]; long total = 0; int read;
                    while ((read = input.read(buffer)) >= 0) {
                        total += read;
                        if (total > properties.getMaxObjectBytes()) throw new IOException("远程图片超过大小限制");
                        output.write(buffer, 0, read);
                    }
                }
            }
            putFile(objectKey, temporary, "image/png");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static URI validateRemoteUri(String raw) throws IOException {
        if (raw == null || raw.isBlank()) throw new IOException("远程图片地址为空");
        URI uri;
        try { uri = URI.create(raw); } catch (IllegalArgumentException e) { throw new IOException("远程图片地址无效", e); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("远程图片地址协议或主机无效");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IOException("禁止访问内网图片地址");
            }
        }
        return uri;
    }

    /** Worker 启动前的轻量配置探测，不会创建 SDK 客户端，也不会打印密钥。 */
    public boolean isConfigured() {
        return configured();
    }

    private OSS client() {
        OSS current = oss;
        if (current != null) return current;
        synchronized (this) {
            if (oss == null) {
                oss = new OSSClientBuilder().build(normalizeEndpoint(properties.getEndpoint()),
                        properties.getAccessKeyId(), properties.getAccessKeySecret());
            }
            return oss;
        }
    }

    private String uploadUrl() {
        String endpoint = normalizeEndpoint(properties.getEndpoint());
        return "https://" + properties.getBucket() + "." + endpoint;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("OSS 未配置，请设置 ALIYUN_OSS_ACCESS_KEY_ID 和 ALIYUN_OSS_ACCESS_KEY_SECRET");
        }
    }

    private boolean configured() {
        return properties.isEnabled()
                && notBlank(properties.getBucket())
                && notBlank(properties.getEndpoint())
                && notBlank(properties.getAccessKeyId())
                && notBlank(properties.getAccessKeySecret());
    }

    private static String normalizeEndpoint(String endpoint) {
        return endpoint.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String hmacSha1Base64(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return base64(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成 OSS 签名失败", e);
        }
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private static boolean isSupportedImage(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    @PreDestroy
    public void close() {
        if (oss != null) oss.shutdown();
    }

    public record UploadPolicy(String uploadUrl, Map<String, String> fields, Instant expiresAt) {}
    public record ArchiveImage(String filename, long size) {}
}
