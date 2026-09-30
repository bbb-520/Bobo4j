package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationSession;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationIntent;
import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.generation.SceneCard;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.image.ImageJobService;
import com.bbb.exercise.agentdemo1_0.memory.VisionMemoryService;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates a chat turn while keeping image generation asynchronous. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int MAX_QUESTION_CODE_POINTS = 4000;
    private static final String DEFAULT_PROMPT = "请根据这张照片进行一次有创意的二次生成。";

    private final ConversationPersistenceService conversations;
    private final ImageJobService imageJobs;
    private final ModelConversationService modelConversation;
    private final VisionMemoryService memories;

    public Mono<ConversationSession> openConversation(String sessionId, ChatIdentity identity) {
        return Mono.fromCallable(() -> conversations.openOrCreate(sessionId, identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ChatEventVO> chat(String question, ConversationSession session,
                                  List<ChatAttachmentRequest> attachments) {
        Instant receivedAt = Instant.now();
        if (session == null) {
            return withCompletionLogging(Flux.just(errorEvent("会话无效，请重新开始"), stopEvent()), "unknown", receivedAt);
        }
        List<ChatAttachmentRequest> images = attachments == null ? List.of() : attachments;
        String invalid = validateQuestion(question, !images.isEmpty());
        if (invalid != null) return withCompletionLogging(Flux.just(errorEvent(invalid), stopEvent()),
                session.conversationId(), receivedAt);
        if (images.size() > 1) {
            return withCompletionLogging(Flux.just(errorEvent("每次只支持一张图片"), stopEvent()),
                    session.conversationId(), receivedAt);
        }
        if (images.isEmpty()) {
            return textOnly(question, session, receivedAt);
        }
        ChatAttachmentRequest attachment = images.get(0);
        if (attachment == null || attachment.getAssetId() == null || attachment.getAssetId().isBlank()) {
            return withCompletionLogging(Flux.just(errorEvent("图片资产 ID 不能为空"), stopEvent()),
                    session.conversationId(), receivedAt);
        }

        ConversationIntent intent = ConversationIntent.classifyText(question, true, false);
        if (intent == ConversationIntent.WAIT_FOR_INSTRUCTION || intent == ConversationIntent.DESCRIBE) {
            return imageAnalysis(question, session, attachment, intent, receivedAt);
        }

        String prompt = question == null || question.isBlank() ? DEFAULT_PROMPT : question.trim();
        String acknowledgement = "已收到照片，正在后台生成一张图片。完成后结果会回到当前对话。";
        String conversationId = session.conversationId();
        Mono<ImageJobService.JobView> create = Mono.fromCallable(() -> {
            ImageJobService.JobView job = imageJobs.create(session.identity(), conversationId, prompt, images);
            conversations.appendTurn(session, prompt, acknowledgement, true);
            return job;
        }).subscribeOn(Schedulers.boundedElastic());

        Flux<ChatEventVO> events = Flux.concat(Flux.just(sessionInfoEvent(conversationId, session.created())),
                        create.flatMapMany(job -> Flux.just(imageJobEvent(job), dataEvent(acknowledgement), stopEvent())))
                .onErrorResume(error -> {
                    log.warn("[chat] image job creation failed cid={} reason={}", conversationId, error.toString());
                    return Flux.just(errorEvent("图片任务创建失败：" + errorMessage(error)), stopEvent());
                });
        return withCompletionLogging(events, conversationId, receivedAt);
    }

    public static String validateQuestion(String question, boolean hasAttachments) {
        if (!hasAttachments && (question == null || question.isBlank())) return "问题不能为空";
        if (question != null && question.codePointCount(0, question.length()) > MAX_QUESTION_CODE_POINTS) {
            return "问题过长（上限 " + MAX_QUESTION_CODE_POINTS + " 字符）";
        }
        return null;
    }

    private Flux<ChatEventVO> textOnly(String question, ConversationSession session, Instant receivedAt) {
        String conversationId = session.conversationId();
        String prompt = question.trim();
        AtomicReference<StringBuilder> answer = new AtomicReference<>(new StringBuilder());
        Flux<ChatEventVO> stream = modelConversation.answer(session.identity(), prompt)
                .doOnNext(answer.get()::append)
                .map(ChatService::dataEvent)
                .concatWith(Mono.<ChatEventVO>fromRunnable(() -> conversations.appendTurn(
                        session, prompt, answer.get().toString(), true))
                        .subscribeOn(Schedulers.boundedElastic()))
                .concatWith(Flux.just(stopEvent()))
                .onErrorResume(error -> Flux.just(errorEvent("对话生成失败：" + errorMessage(error)), stopEvent()));
        return withCompletionLogging(Flux.concat(
                Flux.just(sessionInfoEvent(conversationId, session.created())), stream), conversationId, receivedAt);
    }

    private Flux<ChatEventVO> imageAnalysis(String question, ConversationSession session,
                                             ChatAttachmentRequest attachment,
                                             ConversationIntent intent, Instant receivedAt) {
        String conversationId = session.conversationId();
        String userContent = question == null || question.isBlank() ? "[图片已上传]" : question.trim();
        return Flux.concat(
                Flux.just(sessionInfoEvent(conversationId, session.created())),
                modelConversation.analyze(session.identity(), attachment)
                        .flatMapMany(card -> {
                            String acknowledgement = intent == ConversationIntent.WAIT_FOR_INSTRUCTION
                                    ? "图片已识别。你可以继续询问画面，也可以告诉我如何生成或修改。"
                                    : "图片识别完成。";
                            Mono<Void> save = Mono.fromRunnable(() -> conversations.appendTurn(
                                    session, userContent, card.summary(), true))
                                    .then().subscribeOn(Schedulers.boundedElastic());
                            Mono<Void> remember = Mono.fromRunnable(() -> memories.rememberScene(
                                    session.identity(), card, question)).then().subscribeOn(Schedulers.boundedElastic());
                            Flux<ChatEventVO> result = Flux.just(sceneCardEvent(card), dataEvent(acknowledgement));
                            if (intent == ConversationIntent.WAIT_FOR_INSTRUCTION) {
                                result = result.concatWith(Flux.just(waitingEvent(acknowledgement)));
                            }
                            return save.then(remember).thenMany(result).concatWith(Flux.just(stopEvent()));
                        })
                        .onErrorResume(error -> Flux.just(errorEvent("图片识别失败：" + errorMessage(error)), stopEvent())));
    }

    private static Flux<ChatEventVO> withCompletionLogging(Flux<ChatEventVO> events,
                                                            String conversationId,
                                                            Instant receivedAt) {
        long startedNanos = System.nanoTime();
        return events.doFinally(signal -> log.info(
                "[chat] task_completed conversationId={} receivedAt={} completedAt={} durationMs={} signal={}",
                conversationId, receivedAt, Instant.now(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000), signal));
    }

    private static String errorMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? String.valueOf(error) : message;
    }

    private static ChatEventVO dataEvent(String text) {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue()).eventData(text).build();
    }

    private static ChatEventVO imageJobEvent(ImageJobService.JobView job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.jobId());
        data.put("status", job.status());
        data.put("mode", job.mode());
        data.put("createdAt", job.createdAt());
        data.put("startedAt", job.startedAt());
        data.put("completedAt", job.completedAt());
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.IMAGE_JOB.getValue()).eventData(data).build();
    }

    private static ChatEventVO stopEvent() {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    }

    private static ChatEventVO waitingEvent(String message) {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.WAITING_FOR_INSTRUCTION.getValue())
                .eventData(Map.of("state", "WAITING_FOR_INSTRUCTION", "message", message)).build();
    }

    private static ChatEventVO sceneCardEvent(SceneCard card) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", card.summary());
        data.put("subjects", card.subjects());
        data.put("palette", card.palette());
        data.put("composition", card.composition());
        data.put("mood", card.mood());
        data.put("sourceAssetId", card.sourceAssetId());
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.SCENE_CARD.getValue()).eventData(data).build();
    }

    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue()).eventData(message).build();
    }

    private static ChatEventVO sessionInfoEvent(String conversationId, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("created", created);
        data.put("timestamp", System.currentTimeMillis());
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.SESSION_INFO.getValue()).eventData(data).build();
    }
}
