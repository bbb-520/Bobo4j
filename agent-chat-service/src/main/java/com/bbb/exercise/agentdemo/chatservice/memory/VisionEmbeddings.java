package com.bbb.exercise.agentdemo.chatservice.memory;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.function.LongSupplier;

/** Each Spring AI model is bound to a trusted identity, stable billed call and token budget. */
@FunctionalInterface
public interface VisionEmbeddings {
    Space open(ChatIdentity identity,String callId,Long tokenBudget);
    record Space(EmbeddingModel model,String fingerprint,int dimensions,LongSupplier tokens) {}
}
