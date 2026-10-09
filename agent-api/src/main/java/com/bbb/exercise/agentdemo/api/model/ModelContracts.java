package com.bbb.exercise.agentdemo.api.model;

import java.util.List;

/** Stable, provider-neutral results. Token counts always represent measured provider usage. */
public final class ModelContracts {
    private ModelContracts() {}
    public record CompletionRequest(String callId, String mode, String system, String user, String conversationId,Long tokenBudget) {public CompletionRequest(String callId,String mode,String system,String user,String conversationId){this(callId,mode,system,user,conversationId,null);}}
    public record Completion(String text, long inputTokens, long outputTokens, String model, boolean fallback,long reservedTokens,long unknownTokens) {public Completion(String text,long inputTokens,long outputTokens,String model,boolean fallback){this(text,inputTokens,outputTokens,model,fallback,Math.addExact(inputTokens,outputTokens),0);}}
    public record EmbeddingBatch(List<List<Float>> vectors, long inputTokens,String modelFingerprint) {public EmbeddingBatch(List<List<Float>> vectors,long inputTokens){this(vectors,inputTokens,null);}}
    public record Ranked(int index, double score) {}
    public record Ranking(List<Ranked> results, long inputTokens) {}
    public record GenerationEvent(String type,int generationVersion,java.util.Map<String,Object> payload) {}
}
