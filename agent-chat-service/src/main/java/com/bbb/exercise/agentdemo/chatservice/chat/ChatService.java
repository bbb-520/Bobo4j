package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationSession;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent;
import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.chatservice.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo.chatservice.generation.SceneCard;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.chatservice.memory.VisionMemoryService;
import com.bbb.exercise.agentdemo.chatservice.vo.ChatEventVO;
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
    private final InternalServiceClient mediaClient;
    private final ModelConversationService modelConversation;
    private final VisionMemoryService memories;

    // 1. 会话准备：在适合阻塞任务的调度器中读取或创建会话归属记录。
    public Mono<ConversationSession> openConversation(String sessionId, ChatIdentity identity) {
        return Mono.fromCallable(() -> conversations.openOrCreate(sessionId, identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 2. 单轮分流：按输入和图片意图选择文字回答、图片识别或异步图片生成。
    public Flux<ChatEventVO> chat(
            String question, ConversationSession session, List<ChatAttachmentRequest> attachments) {
        Instant receivedAt = Instant.now();

        if (session == null) {
            return withCompletionLogging(
                    Flux.just(errorEvent("会话无效，请重新开始"), stopEvent()), "unknown", receivedAt);
        }

        // 2.1. 输入检查：在访问模型或媒体服务前限制问题长度及图片数量。
        List<ChatAttachmentRequest> images = attachments == null ? List.of() : attachments;
        String invalid = validateQuestion(question, !images.isEmpty());

        if (invalid != null) {
            return withCompletionLogging(
                    Flux.just(errorEvent(invalid), stopEvent()),
                    session.conversationId(),
                    receivedAt);
        }

        if (images.size() > 1) {
            return withCompletionLogging(
                    Flux.just(errorEvent("每次只支持一张图片"), stopEvent()),
                    session.conversationId(),
                    receivedAt);
        }

        // 2.2. 纯文字路径：无附件时直接进入流式文本回答。
        if (images.isEmpty()) {
            return textOnly(question, session, receivedAt);
        }

        ChatAttachmentRequest attachment = images.get(0);

        if (attachment == null
                || attachment.getAssetId() == null
                || attachment.getAssetId().isBlank()) {
            return withCompletionLogging(
                    Flux.just(errorEvent("图片资产 ID 不能为空"), stopEvent()),
                    session.conversationId(),
                    receivedAt);
        }

        // 2.3. 图片意图：先判断是否只需理解画面，避免上传图片就触发付费生成。
        return modelConversation.classifyImageIntent(session.identity(), attachment, question)
                .flatMapMany(intent -> {
                    if (intent == ConversationIntent.GENERATE) {
                        return generateImage(question, session, attachment, receivedAt);
                    }
                    if (intent == ConversationIntent.DESCRIBE || question == null || question.isBlank()) {
                        return imageAnalysis(question, session, attachment, intent, receivedAt);
                    }
                    return Flux.just(sessionInfoEvent(session.conversationId(), session.created()),
                            waitingEvent("暂时无法确认图片创作意图，请明确说明要理解图片，还是生成或编辑图片。"), stopEvent());
                })
                .onErrorResume(error -> Flux.just(sessionInfoEvent(session.conversationId(), session.created()),
                        errorEvent("图片附件不可用或已过期，请重新上传后再试"), stopEvent()));
    }

    private Flux<ChatEventVO> generateImage(String question, ConversationSession session,
            ChatAttachmentRequest attachment, Instant receivedAt) {
        // 2.4. 图片生成路径：创建后台任务，并把任务 ID 和确认消息返回当前对话。
        String prompt = question == null || question.isBlank() ? DEFAULT_PROMPT : question.trim();
        String acknowledgement = "已收到照片，正在后台生成一张图片。完成后结果会回到当前对话。";
        String conversationId = session.conversationId();
        Mono<InternalServiceClient.MediaJobView> create =
                mediaClient
                        .createMediaJob(
                                conversationId,
                                prompt,
                                attachment.getAssetId(),
                                session.identity().userId(),
                                session.identity().tenantId())
                        .flatMap(
                                job ->
                                        Mono.fromCallable(
                                                        () -> {
                                                            // 2.4.1. 任务创建成功后才保存这一轮确认消息，避免记录不存在的任务。
                                                            conversations.appendTurn(
                                                                    session,
                                                                    prompt,
                                                                    acknowledgement,
                                                                    true);
                                                            return job;
                                                        })
                                                .subscribeOn(Schedulers.boundedElastic()));

        Flux<ChatEventVO> events =
                Flux.concat(
                                Flux.just(sessionInfoEvent(conversationId, session.created())),
                                create.flatMapMany(
                                        job ->
                                                Flux.just(
                                                        imageJobEvent(job),
                                                        dataEvent(acknowledgement),
                                                        stopEvent())))
                        .onErrorResume(
                                error -> {
                                    log.warn(
                                            "[chat] image job creation failed cid={} reason={}",
                                            conversationId,
                                            error.toString());
                                    return Flux.just(
                                            errorEvent("图片任务创建失败：" + errorMessage(error)),
                                            stopEvent());
                                });

        return withCompletionLogging(events, conversationId, receivedAt);
    }

    public static String validateQuestion(String question, boolean hasAttachments) {
        if (!hasAttachments && (question == null || question.isBlank())) {
            return "问题不能为空";
        }

        if (question != null
                && question.codePointCount(0, question.length()) > MAX_QUESTION_CODE_POINTS) {
            return "问题过长（上限 " + MAX_QUESTION_CODE_POINTS + " 字符）";
        }

        return null;
    }

    // 3. 文字回答：累积模型事件，并在完成、失败或取消时保存对应的回答状态。
    private Flux<ChatEventVO> textOnly(
            String question, ConversationSession session, Instant receivedAt) {
        String conversationId = session.conversationId();
        String prompt = question.trim();
        Mono<AtomicReference<StringBuilder>> turn =
                Mono.fromCallable(
                                () -> {
                                    conversations.appendUserMessage(session, prompt);
                                    return new AtomicReference<>(new StringBuilder());
                                })
                        .subscribeOn(Schedulers.boundedElastic());
        // 3.1. 生命周期收尾：正常结束保存完整回答，错误或取消保存未完成内容。
        Flux<ChatEventVO> stream =
                Flux.usingWhen(
                                turn,
                                answer ->
                                        modelConversation
                                                .answerEvents(
                                                        session.identity(), prompt, conversationId)
                                                .doOnNext(
                                                        event -> {
                                                            String text =
                                                                    String.valueOf(
                                                                            event.payload()
                                                                                    .getOrDefault(
                                                                                            "text",
                                                                                            ""));

                                                            if (event.type()
                                                                            .equals(
                                                                                    "answer_replace")
                                                                    || event.type()
                                                                            .equals(
                                                                                    "answer_committed")) {
                                                                answer.set(new StringBuilder(text));
                                                            } else if (event.type()
                                                                    .equals("answer_delta")) {
                                                                answer.get().append(text);
                                                            }
                                                        })
                                                .filter(
                                                        event ->
                                                                List.of(
                                                                                "answer_delta",
                                                                                "answer_replace",
                                                                                "fallback_started",
                                                                                "model_status")
                                                                        .contains(event.type()))
                                                .map(ChatService::generationEvent),
                                answer -> persistAssistant(session, answer.get().toString(), true),
                                (answer, error) ->
                                        persistAssistant(session, answer.get().toString(), false),
                                answer -> persistAssistant(session, answer.get().toString(), false))
                        .concatWith(Flux.just(stopEvent()))
                        .onErrorResume(
                                error ->
                                        Flux.just(
                                                errorEvent("对话生成失败：" + errorMessage(error)),
                                                stopEvent()));

        return withCompletionLogging(
                Flux.concat(Flux.just(sessionInfoEvent(conversationId, session.created())), stream),
                conversationId,
                receivedAt);
    }

    // 3.2. 回答落库：把是否完整作为持久化字段，供后续历史选择使用。
    private Mono<Void> persistAssistant(
            ConversationSession session, String content, boolean completed) {
        return Mono.fromRunnable(
                        () -> conversations.appendAssistantMessage(session, content, completed))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // 4. 图片理解：读取场景事件后，分别保存会话消息和视觉记忆。
    private Flux<ChatEventVO> imageAnalysis(
            String question,
            ConversationSession session,
            ChatAttachmentRequest attachment,
            ConversationIntent intent,
            Instant receivedAt) {
        String conversationId = session.conversationId();
        String userContent = question == null || question.isBlank() ? "[图片已上传]" : question.trim();

        return Flux.concat(
                Flux.just(sessionInfoEvent(conversationId, session.created())),
                modelConversation
                        .analyzeEvents(session.identity(), attachment, question)
                        .concatMap(
                                event -> {
                                    if (!event.type().equals("scene_card")) {
                                        return Flux.just(generationEvent(event));
                                    }

                                    var card =
                                            new SceneCard(
                                                    String.valueOf(event.payload().get("summary")),
                                                    List.of(),
                                                    "",
                                                    "",
                                                    "",
                                                    attachment.getAssetId());
                                    String acknowledgement =
                                            intent == ConversationIntent.WAIT_FOR_INSTRUCTION
                                                    ? "图片已识别。你可以继续询问画面，也可以告诉我如何生成或修改。"
                                                    : "图片识别完成。";
                                    Mono<Void> save =
                                            Mono.fromRunnable(
                                                            () ->
                                                                    conversations.appendTurn(
                                                                            session,
                                                                            userContent,
                                                                            card.summary(),
                                                                            true))
                                                    .then()
                                                    .subscribeOn(Schedulers.boundedElastic());
                                    Mono<Void> remember =
                                            Mono.fromRunnable(
                                                            () ->
                                                                    memories.rememberScene(
                                                                            session.identity(),
                                                                            card,
                                                                            question))
                                                    .then()
                                                    .subscribeOn(Schedulers.boundedElastic());
                                    Flux<ChatEventVO> result =
                                            Flux.just(
                                                    sceneCardEvent(card),
                                                    dataEvent(acknowledgement));

                                    if (intent == ConversationIntent.WAIT_FOR_INSTRUCTION) {
                                        result =
                                                result.concatWith(
                                                        Flux.just(waitingEvent(acknowledgement)));
                                    }

                                    return save.then(remember)
                                            .thenMany(result)
                                            .concatWith(Flux.just(stopEvent()));
                                })
                        .onErrorResume(
                                error ->
                                        Flux.just(
                                                errorEvent("图片识别失败：" + errorMessage(error)),
                                                stopEvent())));
    }

    // 5. 请求观测：统一记录流式请求终止原因和耗时。
    private static Flux<ChatEventVO> withCompletionLogging(
            Flux<ChatEventVO> events, String conversationId, Instant receivedAt) {
        long startedNanos = System.nanoTime();
        return events.doFinally(
                signal ->
                        log.info(
                                "[chat] task_completed conversationId={} receivedAt={} completedAt={} durationMs={} signal={}",
                                conversationId,
                                receivedAt,
                                Instant.now(),
                                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                                signal));
    }

    private static String errorMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? String.valueOf(error) : message;
    }

    // 6. 前端事件协议：将文字、模型状态、图片任务和结束信号封装为统一事件。
    private static ChatEventVO dataEvent(String text) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData(text)
                .build();
    }

    private static ChatEventVO generationEvent(
            com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent event) {
        if (event.type().equals("answer_delta")) {
            return dataEvent(String.valueOf(event.payload().get("text")));
        }

        var type =
                switch (event.type()) {
                    case "answer_replace" -> ChatEventTypeEnum.ANSWER_REPLACE;
                    case "fallback_started" -> ChatEventTypeEnum.FALLBACK_STARTED;
                    default -> ChatEventTypeEnum.MODEL_STATUS;
                };

        return ChatEventVO.builder()
                .eventType(type.getValue())
                .eventData(
                        Map.of(
                                "generationVersion",
                                event.generationVersion(),
                                "payload",
                                event.payload()))
                .build();
    }

    private static ChatEventVO imageJobEvent(InternalServiceClient.MediaJobView job) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("jobId", job.jobId());
        data.put("status", job.status());
        data.put("mode", job.mode());
        data.put("createdAt", job.createdAt());
        data.put("startedAt", job.startedAt());
        data.put("completedAt", job.completedAt());

        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.IMAGE_JOB.getValue())
                .eventData(data)
                .build();
    }

    private static ChatEventVO stopEvent() {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    }

    private static ChatEventVO waitingEvent(String message) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.WAITING_FOR_INSTRUCTION.getValue())
                .eventData(Map.of("state", "WAITING_FOR_INSTRUCTION", "message", message))
                .build();
    }

    private static ChatEventVO sceneCardEvent(SceneCard card) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("summary", card.summary());
        data.put("subjects", card.subjects());
        data.put("palette", card.palette());
        data.put("composition", card.composition());
        data.put("mood", card.mood());
        data.put("sourceAssetId", card.sourceAssetId());

        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.SCENE_CARD.getValue())
                .eventData(data)
                .build();
    }

    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.ERROR.getValue())
                .eventData(message)
                .build();
    }

    private static ChatEventVO sessionInfoEvent(String conversationId, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("conversationId", conversationId);
        data.put("created", created);
        data.put("timestamp", System.currentTimeMillis());

        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.SESSION_INFO.getValue())
                .eventData(data)
                .build();
    }
}
