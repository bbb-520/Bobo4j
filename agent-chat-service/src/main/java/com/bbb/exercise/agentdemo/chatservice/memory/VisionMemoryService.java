package com.bbb.exercise.agentdemo.chatservice.memory;

import com.bbb.exercise.agentdemo.chatservice.generation.SceneCard;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Durable visual vectors. Cosine search never mixes users, tenants, dimensions or model versions. */
@Service
public class VisionMemoryService {
    private final JdbcTemplate jdbc;private final VisionEmbeddings embeddings;private final double threshold;
    private final JsonMapper json=JsonMapper.builder().build();
    public VisionMemoryService(JdbcTemplate jdbc,VisionEmbeddings embeddings,@Value("${app.vision-memory.similarity-threshold:0.55}") double threshold) {
        if(!Double.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("视觉相似度阈值无效");
        this.jdbc=jdbc;this.embeddings=embeddings;this.threshold=threshold;
    }
    public void rememberScene(ChatIdentity identity,SceneCard card,String instruction) {
        requireUser(identity);if(card==null||card.summary().isBlank())return;
        String content=card.summary()+" "+String.join(" ",card.subjects())+" "+card.palette()+" "+card.composition()+" "+card.mood()+(instruction==null?"":" 用户指令："+instruction);
        jdbc.update("INSERT INTO vision_memory(user_id,tenant_id,kind,content,structured_json,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                identity.userId(),identity.tenantId(),"VISUAL_PREFERENCE",content,json.writeValueAsString(card),"SCENE_CARD",LocalDateTime.now(),LocalDateTime.now());
    }
    public SearchResult search(ChatIdentity identity,String prompt,int limit,String callId,Long tokenBudget) {
        requireUser(identity);if(prompt==null||prompt.isBlank()||prompt.length()>8192)throw new IllegalArgumentException("视觉查询无效");
        var rows=jdbc.queryForList("SELECT id,content,embedding_json,embedding_fingerprint,embedding_dimensions FROM vision_memory WHERE user_id=? AND tenant_id=? AND active=1 AND embedding_json IS NOT NULL ORDER BY updated_at DESC LIMIT 1000",identity.userId(),identity.tenantId());
        if(rows.isEmpty())return new SearchResult("",0,0,0);
        var space=embeddings.open(identity,callId,tokenBudget);
        var compatible=rows.stream().filter(r->space.fingerprint().equals(r.get("embedding_fingerprint"))&&r.get("embedding_dimensions") instanceof Number n&&n.intValue()==space.dimensions()).toList();
        if(compatible.isEmpty())return new SearchResult("",0,0,0);
        float[] query=space.model().embed(prompt);validate(query,space.dimensions());
        var ranked=new ArrayList<Hit>();
        for(var row:compatible) {
            try {float[] candidate=json.readValue(String.valueOf(row.get("embedding_json")),float[].class);validate(candidate,space.dimensions());double score=cosine(query,candidate);if(score>=threshold)ranked.add(new Hit(((Number)row.get("id")).longValue(),String.valueOf(row.get("content")),score));}
            catch(RuntimeException corrupt) { /* Corrupt persisted vectors remain excluded until explicit reindex. */ }
        }
        var hits=ranked.stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparingLong(Hit::id)).limit(Math.max(1,Math.min(limit,8))).toList();
        return new SearchResult(hits.stream().map(Hit::content).reduce((a,b)->a+"；"+b).orElse(""),hits.size(),space.tokens().getAsLong(),0);
    }
    /** Owner-triggered bounded migration; no query automatically starts paid legacy reindexing. */
    public int reindex(ChatIdentity identity,int limit) {
        requireUser(identity);if(limit<1||limit>8)throw new IllegalArgumentException("每次最多重建 8 条视觉记忆");
        var current=embeddings.open(identity,stable("space","inspect"),0L);
        var rows=jdbc.queryForList("SELECT id,content,embedding_json,embedding_fingerprint,embedding_dimensions FROM vision_memory WHERE user_id=? AND tenant_id=? AND active=1 ORDER BY id LIMIT 1000",identity.userId(),identity.tenantId());
        int count=0;
        for(var row:rows) {
            boolean valid=false;
            if(current.fingerprint().equals(row.get("embedding_fingerprint"))&&row.get("embedding_dimensions") instanceof Number n&&n.intValue()==current.dimensions()) {
                try {validate(json.readValue(String.valueOf(row.get("embedding_json")),float[].class),current.dimensions());valid=true;}catch(RuntimeException corrupt) { /* Repair below under the same billed call ID. */ }
            }
            if(valid)continue;
            if(count>=limit)break;
            String content=String.valueOf(row.get("content"));var space=embeddings.open(identity,stable("reindex",row.get("id")+":"+current.fingerprint()+":"+content),null);
            if(!space.fingerprint().equals(current.fingerprint()))throw new IllegalStateException("视觉模型在重建期间变更");
            float[] vector=space.model().embed(content);validate(vector,space.dimensions());
            count+=jdbc.update("UPDATE vision_memory SET embedding_json=?,embedding_fingerprint=?,embedding_dimensions=? WHERE id=? AND user_id=? AND tenant_id=? AND content=?",json.writeValueAsString(vector),space.fingerprint(),space.dimensions(),row.get("id"),identity.userId(),identity.tenantId(),content);
        }
        return count;
    }
    private static void requireUser(ChatIdentity identity){if(identity==null||!identity.authenticated()||identity.userId()==null||identity.userId().isBlank()||identity.tenantId()==null||identity.tenantId().isBlank())throw new IllegalStateException("请先登录");}
    private static String stable(String purpose,String text){return "vision:"+purpose+":"+UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));}
    private static void validate(float[] vector,int dimensions){if(vector==null||vector.length!=dimensions)throw new IllegalArgumentException("视觉向量维度不匹配");double norm=0;for(float value:vector){if(!Float.isFinite(value))throw new IllegalArgumentException("无效视觉向量");norm+=(double)value*value;}if(norm==0)throw new IllegalArgumentException("无效零向量");}
    static double cosine(float[] a,float[] b){double dot=0,left=0,right=0;for(int i=0;i<a.length;i++){dot+=(double)a[i]*b[i];left+=(double)a[i]*a[i];right+=(double)b[i]*b[i];}return dot/Math.sqrt(left*right);}
    private record Hit(long id,String content,double score) {}
    public record SearchResult(String context,int hits,long inputTokens,long outputTokens) {}
}
