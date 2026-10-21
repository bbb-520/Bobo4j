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

    // 1. OSS 直传：为指定对象生成限时策略，由业务服务控制资产归属。
    public UploadPolicy createUploadPolicy(String objectKey, String contentType) {
        requireConfigured();

        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("OSS 对象键不能为空");
        }

        String type =
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream"
                        : contentType;
        Instant expiresAt = Instant.now().plus(properties.getUploadPolicyTtl());
        String policyJson =
                "{\"expiration\":\""
                        + expiresAt.toString()
                        + "\",\"conditions\":["
                        + "[\"content-length-range\",0,"
                        + properties.getMaxObjectBytes()
                        + "],"
                        + "{\"key\":\""
                        + objectKey
                        + "\"},"
                        + "{\"Content-Type\":\""
                        + type
                        + "\"}]}";
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

    // 2. 对象确认：读取远端对象元数据，供上传确认与作品发布检查。
    public ObjectMetadata metadata(String objectKey) {
        requireConfigured();
        return client().getObjectMetadata(properties.getBucket(), objectKey);
    }

    /** Lists direct image children only; keys remain private and are never returned to the browser. */
    public List<ArchiveImage> listArchiveImages(String archivePrefix) {
        requireConfigured();
        String normalizedPrefix =
                archivePrefix == null ? "" : archivePrefix.trim().replaceAll("^/+|/+$", "");

        if (normalizedPrefix.isBlank()) {
            throw new IllegalArgumentException("OSS 相册前缀不能为空");
        }

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

                if (key == null || !key.startsWith(objectPrefix)) {
                    continue;
                }

                String filename = key.substring(objectPrefix.length());

                // This album is a flat folder. Ignore subfolders and the console's folder marker
                // object.
                if (filename.isBlank()
                        || filename.length() > 180
                        || filename.contains("..")
                        || filename.contains("/")
                        || filename.contains("\\")
                        || !isSupportedImage(filename)) {
                    continue;
                }

                images.add(new ArchiveImage(filename, object.getSize()));
            }

            if (!listing.isTruncated()) {
                break;
            }

            String nextMarker = listing.getNextMarker();

            if (nextMarker == null || nextMarker.isBlank() || nextMarker.equals(marker)) {
                throw new IllegalStateException("OSS 相册分页标记无效");
            }

            marker = nextMarker;
        } while (true);

        images.sort(
                Comparator.comparing(ArchiveImage::filename, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ArchiveImage::filename));

        return List.copyOf(images);
    }

    // 3. 限时读取：生成受过期时间约束的对象 URL，支持图片处理参数。
    public String signedGetUrl(String objectKey) {
        return signedGetUrl(objectKey, null);
    }

    public String signedGetUrl(String objectKey, String process) {
        return signedGetUrl(objectKey, process, properties.getSignedUrlTtl());
    }

    public String signedGetUrl(String objectKey, String process, Duration ttl) {
        requireConfigured();

        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("OSS 对象键不能为空");
        }

        Duration safeTtl =
                ttl == null || ttl.isNegative() || ttl.isZero()
                        ? properties.getSignedUrlTtl()
                        : ttl;
        Date expires = Date.from(Instant.now().plus(safeTtl));
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(properties.getBucket(), objectKey, HttpMethod.GET);

        request.setExpiration(expires);

        if (process != null && !process.isBlank()) {
            request.setProcess(process);
        }

        return client().generatePresignedUrl(request).toExternalForm();
    }

    // 3.1. 下载地址：在签名请求中设置下载文件名和有效期。
    /** Creates a short private link that asks the browser to save the result as a file. */
    public String signedDownloadUrl(String objectKey, String filename, Duration ttl) {
        requireConfigured();

        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("OSS 对象键不能为空");
        }

        Duration safeTtl =
                ttl == null || ttl.isNegative() || ttl.isZero()
                        ? properties.getSignedUrlTtl()
                        : ttl;
        Date expires = Date.from(Instant.now().plus(safeTtl));
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(properties.getBucket(), objectKey, HttpMethod.GET);

        request.setExpiration(expires);
        String safeFilename =
                filename == null ? "bobo-image.png" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();

        headers.setContentDisposition("attachment; filename=\"" + safeFilename + "\"");
        request.setResponseHeaders(headers);

        return client().generatePresignedUrl(request).toExternalForm();
    }

    // 4. 对象生命周期：删除与写入均使用服务端受控 object key。
    public void deleteObject(String objectKey) {
        requireConfigured();

        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("OSS 对象键不能为空");
        }

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

    // 5. 供应商结果转存：校验远端地址和下载内容后复制到业务预定对象位置。
    /**
     * 把供应商结果落成受控对象。两条输入通道共用同一套边界：
     *
     * <ol>
     *   <li>体积：data URL 先按编码长度预估，再按实际解码字节计数；HTTP 同时校验声明长度与实际读取量。
     *   <li>内容：落盘后按字节特征判定真实格式与像素尺寸，不信任 data URL 或上游的 MIME 声明。
     *   <li>失败：任何异常都关闭流、删除临时文件，不产生对象上传副作用。
     * </ol>
     */
    public void copyRemoteImageToObject(String remoteUrl, String objectKey)
            throws IOException, InterruptedException {
        requireConfigured();
        long maxBytes = properties.getMaxObjectBytes();
        Path temporary = Files.createTempFile("bbb-image-output-", ".bin");

        try {
            if (remoteUrl != null && remoteUrl.startsWith("data:")) {
                writeDataUrl(remoteUrl, temporary, maxBytes);
            } else {
                downloadRemote(remoteUrl, temporary, maxBytes);
            }

            long size = Files.size(temporary);

            if (size <= 0) {
                throw new PermanentDeliveryException("图片结果为空内容");
            }

            if (size > maxBytes) {
                throw new PermanentDeliveryException("图片结果超过大小限制");
            }

            ImageFormatSniffer.ImageFormat format = ImageFormatSniffer.detect(temporary);

            if (format == null) {
                throw new PermanentDeliveryException("图片结果不是受支持的图片格式");
            }

            if (format.exceedsLimits()) {
                throw new PermanentDeliveryException("图片结果像素尺寸超出限制");
            }

            // 上传时使用嗅探出的真实类型，而不是固定 image/png。
            putFile(objectKey, temporary, format.mime());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** data URL 通道：协议、媒体类型、Base64 格式与长度全部前置校验，解码过程仍然有界。 */
    private void writeDataUrl(String dataUrl, Path target, long maxBytes) throws IOException {
        int comma = dataUrl.indexOf(',');

        if (comma < 0) {
            throw new PermanentDeliveryException("图片结果 data URL 无效");
        }

        String header = dataUrl.substring("data:".length(), comma);

        if (!header.toLowerCase(Locale.ROOT).endsWith(";base64")) {
            throw new PermanentDeliveryException("图片结果 data URL 必须使用 Base64 编码");
        }

        String declared =
                header.substring(0, header.length() - ";base64".length());

        if (!ImageFormatSniffer.isAllowedDeclaredType(declared)) {
            throw new PermanentDeliveryException("图片结果 data URL 媒体类型不受支持");
        }

        int payloadStart = comma + 1;
        int encodedLength = dataUrl.length() - payloadStart;

        // 每个 3 字节对应 4 个 Base64 字符；先按上界拒绝，避免为超限内容分配解码缓冲。
        long maxEncoded = ((maxBytes + 2) / 3) * 4 + 4;

        if (encodedLength <= 0) {
            throw new PermanentDeliveryException("图片结果 data URL 缺少内容");
        }

        if (encodedLength > maxEncoded) {
            throw new PermanentDeliveryException("图片结果超过大小限制");
        }

        try (InputStream raw = new Base64PayloadStream(dataUrl, payloadStart);
                InputStream decoded = Base64.getDecoder().wrap(raw)) {
            copyBounded(decoded, target, maxBytes);
        } catch (IllegalArgumentException invalidBase64) {
            throw new PermanentDeliveryException("图片结果 Base64 解码失败", invalidBase64);
        }
    }

    /** HTTP 通道：内网地址与跳转策略不变，声明长度与实际读取量双重限制。 */
    private void downloadRemote(String remoteUrl, Path target, long maxBytes)
            throws IOException, InterruptedException {
        URI uri = validateRemoteUri(remoteUrl);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();

        try (InputStream input = response.body()) {
            if (status < 200 || status >= 300) {
                throw httpFailure(status);
            }

            long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);

            if (declared > maxBytes) {
                throw new PermanentDeliveryException("远程图片超过大小限制");
            }

            copyBounded(input, target, maxBytes);
        }
    }

    /**
     * 把 HTTP 状态映射为交付错误类型：4xx（除 408/429）是确定性拒绝，
     * 5xx、408 与 429 属于可重试的瞬态故障。
     */
    static IOException httpFailure(int status) {
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            return new PermanentDeliveryException("下载图片结果被拒绝，HTTP " + status);
        }

        return new IOException("下载图片结果失败，HTTP " + status);
    }

    /** 有界拷贝：实际字节数超过上限立即终止，不依赖上游声明。 */
    private static void copyBounded(InputStream input, Path target, long maxBytes) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;

            while ((read = input.read(buffer)) >= 0) {
                total += read;

                if (total > maxBytes) {
                    throw new PermanentDeliveryException("图片结果超过大小限制");
                }

                output.write(buffer, 0, read);
            }
        }
    }

    /** 把 data URL 载荷当作 Base64 字符流，拒绝任何非 Base64 字节，避免整段复制。 */
    private static final class Base64PayloadStream extends InputStream {
        private final String source;
        private int index;

        Base64PayloadStream(String source, int start) {
            this.source = source;
            this.index = start;
        }

        @Override
        public int read() throws IOException {
            while (index < source.length()) {
                char value = source.charAt(index++);

                if (value == '\n' || value == '\r' || value == ' ' || value == '\t') {
                    continue;
                }

                boolean base64 =
                        (value >= 'A' && value <= 'Z')
                                || (value >= 'a' && value <= 'z')
                                || (value >= '0' && value <= '9')
                                || value == '+'
                                || value == '/'
                                || value == '=';

                if (!base64) {
                    throw new PermanentDeliveryException("图片结果 Base64 含非法字符");
                }

                return value;
            }

            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            for (int i = 0; i < length; i++) {
                int value = read();

                if (value < 0) {
                    return i == 0 ? -1 : i;
                }

                buffer[offset + i] = (byte) value;
            }

            return length;
        }
    }

    // 5.1. 远端地址边界：拒绝不允许的协议和地址，限制远程图片读取范围。
    static URI validateRemoteUri(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("远程图片地址为空");
        }

        URI uri;

        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IOException("远程图片地址无效", e);
        }

        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IOException("远程图片地址协议或主机无效");
        }

        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
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

        if (current != null) {
            return current;
        }

        synchronized (this) {
            if (oss == null) {
                oss =
                        new OSSClientBuilder()
                                .build(
                                        "https://" + normalizeEndpoint(properties.getEndpoint()),
                                        properties.getAccessKeyId(),
                                        properties.getAccessKeySecret());
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
            throw new IllegalStateException(
                    "OSS 未配置，请设置 ALIYUN_OSS_ACCESS_KEY_ID 和 ALIYUN_OSS_ACCESS_KEY_SECRET");
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

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isSupportedImage(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    @PreDestroy
    public void close() {
        if (oss != null) {
            oss.shutdown();
        }
    }

    /** 对象存储直传策略，包含上传地址、签名表单字段及授权到期时间。 */
    public record UploadPolicy(String uploadUrl, Map<String, String> fields, Instant expiresAt) {}

    /** 归档图片的文件描述，记录归档后的文件名和大小。 */
    public record ArchiveImage(String filename, long size) {}
}
