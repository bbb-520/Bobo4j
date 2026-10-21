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

    /**
     * 统计该文档在所有版本上的向量条数（F12）。
     *
     * <p>删除清理需要一个"到底清干净没有"的判据。{@link #verify} 是按版本比对的写后校验，
     * 不适合删除后核对（版本已随重建递增，且删除要求所有版本都为 0）。</p>
     *
     * <p>默认返回 {@code -1} 表示"实现不支持计数"：调用方必须据此区分"确认干净"与"无法确认"，
     * 不能把未知当成 0。生产实现（Milvus）会覆盖本方法返回真实条数。</p>
     */
    default int count(ChatIdentity who, String document) {
        return -1;
    }
}
