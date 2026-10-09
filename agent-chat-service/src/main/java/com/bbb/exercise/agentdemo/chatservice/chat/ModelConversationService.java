package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo.chatservice.generation.SceneCard;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.runtime.client.AuthModelClient;
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
public class ModelConversationService {
    private static final String VISION_INSTRUCTION = "请分析这张图片，返回简洁的 Scene Card：" +
            "主体、构图、主色、材质和情绪。不要输出思维链，不要编造图片中没有的内容。";

    private final AuthModelClient profiles;
    private final UserChatClientFactory clients;
    private final InternalServiceClient mediaClient;
    private final com.bbb.exercise.agentdemo.runtime.client.BillingClient billing;
    private final com.bbb.exercise.agentdemo.runtime.client.ModelCallClient models;
    private final com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService conversations;
    public ModelConversationService(AuthModelClient profiles,UserChatClientFactory clients,InternalServiceClient mediaClient,com.bbb.exercise.agentdemo.runtime.client.BillingClient billing){this(profiles,clients,mediaClient,billing,null,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public ModelConversationService(AuthModelClient profiles,UserChatClientFactory clients,InternalServiceClient mediaClient,com.bbb.exercise.agentdemo.runtime.client.BillingClient billing,com.bbb.exercise.agentdemo.runtime.client.ModelCallClient models,com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService conversations){this.profiles=profiles;this.clients=clients;this.mediaClient=mediaClient;this.billing=billing;this.models=models;this.conversations=conversations;}
    public Flux<com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent> answerEvents(ChatIdentity identity,String prompt,String conversationId){
        return Flux.create(sink -> {var worker=Schedulers.boundedElastic().schedule(() -> {try{String user=prompt;if(conversationId!=null&&conversations!=null){var session=conversations.openOrCreate(conversationId,identity);String history=conversations.committedHistory(session);if(!history.isBlank())user="【已提交会话历史，仅用于理解指代】\n"+history+"\n【当前问题】\n"+prompt;}models.completeStreaming(identity,"chat:"+java.util.UUID.randomUUID(),"CHAT","",user,true,event -> {if(sink.isCancelled())throw new java.util.concurrent.CancellationException();sink.next(event);});if(!sink.isCancelled())sink.complete();}catch(Throwable error){if(!sink.isCancelled())sink.error(error);}});sink.onCancel(worker::dispose);});
    }

    public Flux<String> answer(ChatIdentity identity, String prompt) {
        return answer(identity, prompt, null);
    }

    public Flux<String> answer(ChatIdentity identity, String prompt, String conversationId) {
        return answerEvents(identity,prompt,conversationId).filter(e -> e.type().equals("answer_committed")).map(e -> String.valueOf(e.payload().get("text")));
    }

    public Mono<SceneCard> analyze(ChatIdentity identity, ChatAttachmentRequest attachment) {
        if (attachment == null || attachment.getAssetId() == null || attachment.getAssetId().isBlank()) {
            return Mono.error(new IllegalArgumentException("图片资产 ID 不能为空"));
        }
        return Mono.fromCallable(() -> require(identity,ModelCapability.VISION)).subscribeOn(Schedulers.boundedElastic()).flatMap(selected ->
                mediaClient.mediaAsset(attachment.getAssetId(),identity.userId(),identity.tenantId()).flatMap(asset -> {
                    String id="vision:"+java.util.UUID.randomUUID();
                    return Mono.usingWhen(billing.reserve(identity,id,"VISION",selected.model()),
                            reservation -> billing.dispatch(identity,id).then(Mono.fromCallable(() -> {URL source=new URL(asset.downloadUrl());return clients.create(selected).prompt().user(user -> user.text(VISION_INSTRUCTION)
                                    .media(MimeTypeUtils.parseMimeType(asset.mimeType()),source)).call().chatResponse();}).subscribeOn(Schedulers.boundedElastic()))
                                    .flatMap(response -> {var usage=measured(response);if(usage==null)return Mono.error(new IllegalStateException("识图模型未返回真实 token 用量"));
                                        return billing.settle(identity,id,usage).thenReturn(new SceneCard(response.getResult().getOutput().getText(),List.of(),"","","",asset.assetId()));}),
                            reservation -> Mono.empty(),(reservation,error) -> billing.unknown(identity,id).onErrorResume(ignored -> Mono.empty()),
                            reservation -> billing.unknown(identity,id).onErrorResume(ignored -> Mono.empty()));
                }));
    }

    private AuthModelClient.SelectedModel require(ChatIdentity identity, ModelCapability capability) {
        AuthModelClient.SelectedModel selected = profiles.resolve(identity, capability);
        if (selected == null) {
            throw new IllegalStateException("请先在模型设置中配置 " + capability + " 模型");
        }
        return selected;
    }
    private static com.bbb.exercise.agentdemo.api.billing.BillingContracts.Settlement measured(org.springframework.ai.chat.model.ChatResponse response) {
        if(response==null||response.getMetadata()==null) return null;var usage=response.getMetadata().getUsage();
        if(usage==null||usage.getPromptTokens()==null||usage.getCompletionTokens()==null||usage.getPromptTokens()<=0||usage.getCompletionTokens()<0) return null;
        return new com.bbb.exercise.agentdemo.api.billing.BillingContracts.Settlement(usage.getPromptTokens().longValue(),usage.getCompletionTokens().longValue(),response.getMetadata().getId());
    }
}
