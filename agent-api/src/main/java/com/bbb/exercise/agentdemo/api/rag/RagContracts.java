package com.bbb.exercise.agentdemo.api.rag;

import java.util.List;
import java.util.Map;

/** Unwrapped transport values; identity is resolved from trusted HTTP context. */
public final class RagContracts {
    private RagContracts() {}
    public record Source(String id, String documentId, String chunkId, String title, Integer page, String excerpt) {}
    public record Evidence(String chunkId, String documentId, int indexVersion, String text, String title,
                           Integer page, int sourceOrdinal, double score) {}
    public record RetrieveRequest(String conversationId, String question, String callId,Long tokenBudget) {
        public RetrieveRequest(String conversationId,String question){this(conversationId,question,null,null);}
        public RetrieveRequest(String conversationId,String question,String callId){this(conversationId,question,callId,null);}
    }
    public record Retrieval(String query, List<Evidence> evidence, boolean degraded, long inputTokens, long outputTokens,long budgetTokens,long unknownReservedTokens) {
        public Retrieval(String query,List<Evidence> evidence,boolean degraded){this(query,evidence,degraded,0,0,0,0);}
        public Retrieval(String query,List<Evidence> evidence,boolean degraded,long input,long output){this(query,evidence,degraded,input,output,input+output,0);}
    }
    public record AnswerRequest(String callId, String conversationId, String question,Long tokenBudget) {
        public AnswerRequest(String callId,String conversationId,String question){this(callId,conversationId,question,null);}
    }
    public record Answer(String text, List<Source> sources, String evaluation, boolean degraded,
                         long inputTokens, long outputTokens,long budgetTokens,long unknownReservedTokens) {
        public Answer(String text,List<Source> sources,String evaluation,boolean degraded,long input,long output){this(text,sources,evaluation,degraded,input,output,input+output,0);}
    }
    public record DocumentView(String documentId, String filename, String status, String indexStatus,
                               String summaryStatus, int chunkCount, int completedChunks, String error,
                               int indexVersion, String updatedAt) {}
    public record SummaryView(String status, String text, double coverage) {}
    public record ConversationRequest(List<String> documentIds) {}
    public record ConversationView(String conversationId, List<String> documentIds,
                                   Map<String,Integer> indexVersions, List<History> history) {}
    public record History(String callId, String question, Answer answer) {}
}
