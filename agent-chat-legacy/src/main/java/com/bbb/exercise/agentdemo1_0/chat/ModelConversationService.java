package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.generation.SceneCard;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.image.ImageAssetService;
import com.bbb.exercise.agentdemo1_0.model.ModelCapability;
import com.bbb.exercise.agentdemo1_0.model.ModelProfileService;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
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

    private final ModelProfileService profiles;
    private final UserChatClientFactory clients;
    private final ImageAssetService assets;
    private final OssStorageService storage;

    public Flux<String> answer(ChatIdentity identity, String prompt) {
        ModelProfileService.SelectedModel selected = require(identity, ModelCapability.CHAT);
        return clients.create(selected).prompt().user(prompt).stream().content();
    }

    public Mono<SceneCard> analyze(ChatIdentity identity, ChatAttachmentRequest attachment) {
        if (attachment == null || attachment.getAssetId() == null || attachment.getAssetId().isBlank()) {
            return Mono.error(new IllegalArgumentException("图片资产 ID 不能为空"));
        }
        ModelProfileService.SelectedModel selected = require(identity, ModelCapability.VISION);
        return Mono.fromCallable(() -> assets.requireReady(identity, attachment.getAssetId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(asset -> Mono.fromCallable(() -> {
                    URL source = new URL(storage.signedGetUrl(asset.objectKey()));
                    String result = clients.create(selected).prompt().user(user -> user
                            .text(VISION_INSTRUCTION)
                            .media(MimeTypeUtils.parseMimeType(asset.mimeType()), source))
                            .call().content();
                    return new SceneCard(result, List.of(), "", "", "", asset.id());
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private ModelProfileService.SelectedModel require(ChatIdentity identity, ModelCapability capability) {
        ModelProfileService.SelectedModel selected = profiles.resolve(identity, capability);
        if (selected == null) {
            throw new IllegalStateException("请先在模型设置中配置 " + capability + " 模型");
        }
        return selected;
    }
}
