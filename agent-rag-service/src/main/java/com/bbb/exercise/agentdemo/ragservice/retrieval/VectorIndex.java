package com.bbb.exercise.agentdemo.ragservice.retrieval;

import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import java.util.*;

/** 文档向量索引契约，统一写入、向量和关键词召回、删除及写后验证。 */
public interface VectorIndex {
    void upsert(ChatIdentity who, List<Chunk> chunks, List<List<Float>> vectors);

    List<Hit> dense(Scope scope, List<Float> vector, int limit);

    List<Hit> keyword(Scope scope, String query, int limit);

    void remove(ChatIdentity who, String document);

    void verify(ChatIdentity who, String document, int version, int expected);
}
