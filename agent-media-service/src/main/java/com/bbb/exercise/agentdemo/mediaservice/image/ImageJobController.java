package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;

/** 图片生成任务状态、会话任务与作品档案接口。 */
@RestController
@RequestMapping("/api/image-jobs")
@RequiredArgsConstructor
public class ImageJobController {
    private final ChatIdentityResolver identityResolver;
    private final ImageJobService jobs;

    @PostMapping
    public Mono<ResponseEntity<ImageJobService.JobView>> create(
            @RequestBody CreateRequest request, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> {
                    if (!Boolean.TRUE.equals(request.imageGenerationConfirmed())) {
                        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "请先明确确认本次图片生成或编辑操作"));
                    }
                    return Mono.fromCallable(() -> ResponseEntity.accepted().body(
                        jobs.create(identity, request.requestId(), request.conversationId(),
                                request.prompt(), request.attachments())));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/by-request/{requestId}")
    public Mono<ResponseEntity<ImageJobService.JobView>> byRequest(
            @PathVariable String requestId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(jobs.getByRequest(identity, requestId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateRequest(String requestId, String conversationId, String prompt,
                                List<ChatAttachmentRequest> attachments, Boolean imageGenerationConfirmed) {
        public CreateRequest(String requestId, String conversationId, String prompt, List<ChatAttachmentRequest> attachments) {
            this(requestId, conversationId, prompt, attachments, false);
        }
    }

    // 1. 任务查询：任务状态和下载入口均使用当前认证身份核对归属。
    @GetMapping("/{jobId}")
    public Mono<ResponseEntity<ImageJobService.JobView>> get(
            @PathVariable String jobId, ServerWebExchange exchange) {
        return identityResolver
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                        () -> ResponseEntity.ok(jobs.get(identity, jobId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{jobId}/download")
    public Mono<ResponseEntity<Void>> download(
            @PathVariable String jobId, ServerWebExchange exchange) {
        return identityResolver
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                        () ->
                                                ResponseEntity.status(HttpStatus.FOUND)
                                                        .location(
                                                                URI.create(
                                                                        jobs.downloadUrl(
                                                                                identity, jobId)))
                                                        .<Void>build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 2. 会话图片列表：查询当前用户在指定会话内创建的图片任务。
    @GetMapping
    public Mono<ResponseEntity<List<ImageJobService.JobView>>> conversation(
            @RequestParam String conversationId, ServerWebExchange exchange) {
        return identityResolver
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                        () ->
                                                ResponseEntity.ok(
                                                        jobs.conversation(
                                                                identity, conversationId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 3. 结果归档入口：分页返回当前用户的成功图片。
    @GetMapping("/archive")
    public Mono<ResponseEntity<List<ImageJobService.JobView>>> archive(
            @RequestParam(defaultValue = "24") int limit, ServerWebExchange exchange) {
        return identityResolver
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                        () -> ResponseEntity.ok(jobs.archive(identity, limit))))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
