package com.bbb.exercise.agentdemo.contentservice.photo;

import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 私有 OSS 相册的读取入口。
 *
 * <p>浏览器请求本地 API，由服务端把固定前缀下的图片对象重定向到短期签名 URL；
 * AccessKey 永远不会发送到浏览器，Bucket 也无需改成 public-read。</p>
 */
@RestController
@RequestMapping("/api/photo-archive")
public class PhotoArchiveController {
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final String ARCHIVE_PROCESS = "image/resize,w_1024/quality,q_82/format,webp";

    private final OssProperties properties;
    private final OssStorageService storage;

    public PhotoArchiveController(OssProperties properties, OssStorageService storage) {
        this.properties = properties;
        this.storage = storage;
    }

    @GetMapping
    public Mono<List<OssStorageService.ArchiveImage>> files() {
        return Mono.fromCallable(() -> storage.listArchiveImages(properties.getArchivePrefix()))
                .map(images -> images.stream().filter(image -> supportedImage(image.filename())).toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalStateException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS 相册暂不可用", error));
    }

    @GetMapping("/{fileName:.+}")
    public Mono<ResponseEntity<Void>> image(@PathVariable String fileName,
                                             @RequestParam(defaultValue = "false") boolean original) {
        return redirect(properties.getArchivePrefix(), fileName, original ? null : ARCHIVE_PROCESS);
    }

    private Mono<ResponseEntity<Void>> redirect(String prefix, String fileName, String process) {
        return Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String safeName = validateFileName(fileName);
            String objectKey = joinPrefix(prefix, safeName);
            URI signedUrl = URI.create(storage.signedGetUrl(objectKey, process));
            return ResponseEntity.<Void>status(HttpStatus.FOUND)
                    .location(signedUrl)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .build();
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalStateException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS 相册暂不可用", error));
    }

    private static String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_LENGTH
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的相册文件名");
        }
        if (!supportedImage(fileName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持图片文件");
        }
        return fileName;
    }

    private static boolean supportedImage(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private static String joinPrefix(String prefix, String fileName) {
        String normalized = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置 OSS 相册前缀");
        }
        return normalized + "/" + fileName;
    }
}
