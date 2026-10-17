package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.chatservice.generation.SceneCard;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.client.AuthModelClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/** Provider-neutral text and vision calls used by the conversation state machine. */
@Service
@lombok.extern.slf4j.Slf4j
public class ModelConversationService {
    private static final String VISION_INSTRUCTION =
            "请分析这张图片，返回简洁的 Scene Card：" + "主体、构图、主色、材质和情绪。不要输出思维链，不要编造图片中没有的内容。";

    private final InternalServiceClient mediaClient;
    private final com.bbb.exercise.agentdemo.runtime.client.ModelCallClient models;
    private final com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService
            conversations;

    public ModelConversationService(
            AuthModelClient profiles,
            UserChatClientFactory clients,
            InternalServiceClient mediaClient,
            com.bbb.exercise.agentdemo.runtime.client.BillingClient billing) {
        this(profiles, clients, mediaClient, billing, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelConversationService(
            AuthModelClient profiles,
            UserChatClientFactory clients,
            InternalServiceClient mediaClient,
            com.bbb.exercise.agentdemo.runtime.client.BillingClient billing,
            com.bbb.exercise.agentdemo.runtime.client.ModelCallClient models,
            com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService
                    conversations) {
        this.mediaClient = mediaClient;
        this.models = models;
        this.conversations = conversations;
    }

    /** Resolve ownership before a paid classification; an uncertain verdict never starts generation. */
    public Mono<com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent> classifyImageIntent(
            ChatIdentity identity, ChatAttachmentRequest attachment, String question) {
        var waiting = com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.WAIT_FOR_INSTRUCTION;
        if (question == null || question.isBlank()) return Mono.just(waiting);
        return mediaClient.mediaAsset(attachment.getAssetId(), identity.userId(), identity.tenantId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("图片资产不可用")))
                .flatMap(asset -> classifyImageQuestion(identity, question))
                .map(intent -> intent == com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.REMIX
                        ? com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.GENERATE : intent);
    }

    /** Classify only the user's instruction; asset access remains with the eventual action. */
    public Mono<com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent> classifyImageQuestion(
            ChatIdentity identity, String question) {
        var waiting = com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.WAIT_FOR_INSTRUCTION;
        if (question == null || question.isBlank()) return Mono.just(waiting);
        return Mono.fromCallable(() -> {
                    var result = models.complete(identity, "intent:" + java.util.UUID.randomUUID(),
                            "CLASSIFY", "", question, false);
                    return switch (result.text().strip()) {
                        case "GENERATE" -> com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.GENERATE;
                        case "REMIX" -> com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.REMIX;
                        case "DESCRIBE" -> com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent.DESCRIBE;
                        default -> waiting;
                    };
                }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
                    log.warn("[chat] image intent unavailable errorType={}", error.getClass().getSimpleName());
                    return Mono.just(waiting);
                });
    }

    // 1. 文本模型会话：结合已提交历史和视觉记忆组织模型输入。
    public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> answerEvents(
            ChatIdentity identity, String prompt, String conversationId) {
        return Flux.defer(() -> {
            String history = "";
            if (conversationId != null && conversations != null) {
                history = conversations.committedHistory(conversations.openOrCreate(conversationId, identity));
            }
            return answerEvents(identity, prompt, conversationId, history);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** The caller captures history before saving this user turn; never reload it after that save. */
    public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> answerEvents(
            ChatIdentity identity, String prompt, String conversationId, String committedHistory) {
        return Flux.create(
                sink -> {
                    var worker =
                            Schedulers.boundedElastic()
                                    .schedule(
                                            () -> {
                                                try {
                                                    String user = prompt;

                                                    if (committedHistory != null && !committedHistory.isBlank()) {
                                                            user =
                                                                    "【已提交会话历史，仅用于理解指代】\n"
                                                                            + committedHistory
                                                                            + "\n【当前问题】\n"
                                                                            + prompt;
                                                    }

                                                    models.completeStreaming(
                                                            identity,
                                                            "chat:" + java.util.UUID.randomUUID(),
                                                            "CHAT",
                                                            "",
                                                            user,
                                                            true,
                                                            event -> {
                                                                if (sink.isCancelled()) {
                                                                    throw new java.util.concurrent
                                                                            .CancellationException();
                                                                }

                                                                sink.next(event);
                                                            });

                                                    if (!sink.isCancelled()) {
                                                        sink.complete();
                                                    }
                                                } catch (Throwable error) {
                                                    if (!sink.isCancelled()) {
                                                        sink.error(error);
                                                    }
                                                }
                                            });
                    sink.onCancel(worker::dispose);
                });
    }

    public Flux<String> answer(ChatIdentity identity, String prompt) {
        return answer(identity, prompt, null);
    }

    public Flux<String> answer(ChatIdentity identity, String prompt, String conversationId) {
        return answerEvents(identity, prompt, conversationId)
                .filter(e -> e.type().equals("answer_committed"))
                .map(e -> String.valueOf(e.payload().get("text")));
    }

    // 2. 视觉模型调用：读取已授权图片资产，生成可供会话保存的场景信息。
    public Mono<SceneCard> analyze(ChatIdentity identity, ChatAttachmentRequest attachment) {
        return analyzeEvents(identity, attachment, null)
                .filter(e -> e.type().equals("scene_card"))
                .map(
                        e ->
                                new SceneCard(
                                        String.valueOf(e.payload().get("summary")),
                                        List.of(),
                                        "",
                                        "",
                                        "",
                                        String.valueOf(e.payload().get("sourceAssetId"))))
                .single();
    }

    // 2.1. 视觉事件路径：将模型状态和最终场景结果转换为聊天可消费的事件。
    public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> analyzeEvents(
            ChatIdentity identity, ChatAttachmentRequest attachment, String question) {
        if (attachment == null
                || attachment.getAssetId() == null
                || attachment.getAssetId().isBlank()) {
            return Flux.error(new IllegalArgumentException("图片资产 ID 不能为空"));
        }

        return mediaClient
                .mediaAsset(attachment.getAssetId(), identity.userId(), identity.tenantId())
                .flatMapMany(
                        asset ->
                                Flux.create(
                                        sink -> {
                                            var worker =
                                                    Schedulers.boundedElastic()
                                                            .schedule(
                                                                    () -> {
                                                                        try {
                                                                            String prompt =
                                                                                    question == null
                                                                                                    || question
                                                                                                            .isBlank()
                                                                                            ? VISION_INSTRUCTION
                                                                                            : question
                                                                                                    .trim();
                                                                            var answer =
                                                                                    models
                                                                                            .completeVision(
                                                                                                    identity,
                                                                                                    "vision:"
                                                                                                            + java
                                                                                                                    .util
                                                                                                                    .UUID
                                                                                                                    .randomUUID(),
                                                                                                    VISION_INSTRUCTION,
                                                                                                    prompt,
                                                                                                    asset
                                                                                                            .downloadUrl(),
                                                                                                    event -> {
                                                                                                        if (sink
                                                                                                                .isCancelled()) {
                                                                                                            throw new java
                                                                                                                    .util
                                                                                                                    .concurrent
                                                                                                                    .CancellationException();
                                                                                                        }

                                                                                                        if (List
                                                                                                                .of(
                                                                                                                        "model_status",
                                                                                                                        "fallback_started")
                                                                                                                .contains(
                                                                                                                        event
                                                                                                                                .type())) {
                                                                                                            sink
                                                                                                                    .next(
                                                                                                                            event);
                                                                                                        }
                                                                                                    });

                                                                            if (!sink
                                                                                    .isCancelled()) {
                                                                                sink.next(
                                                                                        new com.bbb
                                                                                                .exercise
                                                                                                .agentdemo
                                                                                                .api
                                                                                                .model
                                                                                                .ModelContracts
                                                                                                .GenerationEvent(
                                                                                                "scene_card",
                                                                                                answer
                                                                                                                .fallback()
                                                                                                        ? 2
                                                                                                        : 1,
                                                                                                java
                                                                                                        .util
                                                                                                        .Map
                                                                                                        .of(
                                                                                                                "summary",
                                                                                                                answer
                                                                                                                        .text(),
                                                                                                                "sourceAssetId",
                                                                                                                asset
                                                                                                                        .assetId())));
                                                                                sink.complete();
                                                                            }
                                                                        } catch (Throwable error) {
                                                                            if (!sink
                                                                                    .isCancelled()) {
                                                                                sink.error(error);
                                                                            }
                                                                        }
                                                                    });
                                            sink.onCancel(worker::dispose);
                                        }));
    }
}
