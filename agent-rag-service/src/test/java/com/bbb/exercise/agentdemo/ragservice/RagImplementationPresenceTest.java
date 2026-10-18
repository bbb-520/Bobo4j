package com.bbb.exercise.agentdemo.ragservice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 验证隔离解析、语义分块及混合检索等核心 RAG 实现已接入项目。 */
class RagImplementationPresenceTest {
    @Test
    void isolatedParserAndSemanticBoundaryAndFusionAreImplemented() {
        for (String type :
                new String[] {
                    "parsing.IsolatedTikaParser",
                    "chunking.SemanticChunker",
                    "retrieval.HybridRetriever"
                }) {
            assertThatCode(() -> Class.forName("com.bbb.exercise.agentdemo.ragservice." + type))
                    .as("Required production component %s", type)
                    .doesNotThrowAnyException();
        }
    }
}
