package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.generation.SceneCard;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.client.InternalServiceClient;
import com.bbb.exercise.agentdemo1_0.model.ModelCapability;
import com.bbb.exercise.agentdemo.common.client.AuthModelClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URL;
import java.util.List;

/** Provider-neutral text and vision calls used by the conversation state machine. */
@Service
@RequiredArgsConstructor
public class ModelConversationService {
    private static final String VISION_INSTRUCTION = "请分析这张图片，返回简洁的 Scene Card：" +
            "主体、构图、主色、材质和情绪。不要输出思维链，不要编造图片中没有的内容。";

    private final AuthModelClient profiles;
    private final UserChatClientFactory clients;
    private final InternalServiceClient mediaClient;

    public Flux<String> answer(ChatIdentity identity, String prompt) {
        return answer(identity, prompt, null);
    }

    public Flux<String> answer(ChatIdentity identity, String prompt, String conversationId) {
        AuthModelClient.SelectedModel selected = require(identity, ModelCapability.CHAT);
        var request = clients.create(selected).prompt().user(prompt);
        if (conversationId != null && !conversationId.isBlank()) {
            request = request.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID,
                    "chat:" + identity.tenantId() + ":" + identity.userId() + ":" + conversationId));
        }
        return request.stream().content();
    }

    public Mono<SceneCard> analyze(ChatIdentity identity, ChatAttachmentRequest attachment) {
        if (attachment == null || attachment.getAssetId() == null || attachment.getAssetId().isBlank()) {
            return Mono.error(new IllegalArgumentException("图片资产 ID 不能为空"));
        }
        AuthModelClient.SelectedModel selected = require(identity, ModelCapability.VISION);
        return mediaClient.mediaAsset(attachment.getAssetId(), identity.userId(), identity.tenantId())
                .flatMap(asset -> Mono.fromCallable(() -> {
                    URL source = new URL(asset.downloadUrl());
                    String result = clients.create(selected).prompt().user(user -> user
                            .text(VISION_INSTRUCTION)
                            .media(MimeTypeUtils.parseMimeType(asset.mimeType()), source))
                            .call().content();
                    return new SceneCard(result, List.of(), "", "", "", asset.assetId());
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private AuthModelClient.SelectedModel require(ChatIdentity identity, ModelCapability capability) {
        AuthModelClient.SelectedModel selected = profiles.resolve(identity, capability);
        if (selected == null) {
            throw new IllegalStateException("请先在模型设置中配置 " + capability + " 模型");
        }
        return selected;
    }
}
