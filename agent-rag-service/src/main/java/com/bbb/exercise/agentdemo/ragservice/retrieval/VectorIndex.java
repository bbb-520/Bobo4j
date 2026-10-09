package com.bbb.exercise.agentdemo.ragservice.retrieval;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import java.util.*;
public interface VectorIndex {
    void upsert(ChatIdentity who,List<Chunk> chunks,List<List<Float>> vectors);
    List<Hit> dense(Scope scope,List<Float> vector,int limit);
    List<Hit> keyword(Scope scope,String query,int limit);
    void remove(ChatIdentity who,String document);
    void verify(ChatIdentity who,String document,int version,int expected);
}
