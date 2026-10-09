package com.bbb.exercise.agentdemo.ragservice.domain;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import java.util.List;
import java.util.Map;

public final class RagData {
    private RagData() {}
    public record Block(String blockId, String kind, String title, String text, int ordinal,
                        int charStart, int charEnd, Integer page) {}
    public record Chunk(String id, String documentId, int version, String text, String title, Integer page,
                        int ordinal, int charStart, int charEnd, String hash,String embeddingFingerprint) {
        public Chunk(String id,String documentId,int version,String text,String title,Integer page,int ordinal,int charStart,int charEnd,String hash){this(id,documentId,version,text,title,page,ordinal,charStart,charEnd,hash,null);}
    }
    public record Scope(ChatIdentity identity, Map<String,Integer> versions,String embeddingFingerprint) {
        public Scope(ChatIdentity identity,Map<String,Integer> versions){this(identity,versions,null);}
        public Scope { versions = Map.copyOf(versions); if (versions.isEmpty()) throw new IllegalArgumentException("Empty document scope"); }
    }
    public record Hit(String chunkId, String documentId, int version, String tenant, String user, double score) {}
    public record Job(String id, ChatIdentity identity, int version, long fence, String stage, String path, String filename) {}
    public record Parsed(List<Block> blocks, String mediaType, int characters) {}
}
