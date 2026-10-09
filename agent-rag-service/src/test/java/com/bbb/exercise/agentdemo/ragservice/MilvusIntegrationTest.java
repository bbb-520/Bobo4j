package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.retrieval.MilvusVectorIndex;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import io.milvus.v2.client.*;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="RAG_MILVUS_INTEGRATION",matches="true")
class MilvusIntegrationTest {
    @Test void realDenseAndBm25AndMixedChineseNumbersRespectOwnerVersionAndReadback(){
        String uri=System.getenv().getOrDefault("MILVUS_URI","http://127.0.0.1:19530");String collection="rag_test_"+UUID.randomUUID().toString().replace("-","");
        var index=new MilvusVectorIndex(uri,"",collection,4);var who=new ChatIdentity("test-tenant","owner",true);var other=new ChatIdentity("test-tenant","other",true);
        try{
            String id="a".repeat(64),old="b".repeat(64),foreign="c".repeat(64);String document="document";
            index.upsert(who,List.of(new Chunk(id,document,2,"阀门 ZX-204 允许压力为97千帕。","Valve",null,0,0,24,"h")),List.of(List.of(1f,0f,0f,0f)));
            index.upsert(who,List.of(new Chunk(old,document,1,"阀门 ZX-204 允许压力为42千帕。","Old",null,0,0,24,"h2")),List.of(List.of(1f,0f,0f,0f)));
            index.upsert(other,List.of(new Chunk(foreign,document,2,"阀门 ZX-204 允许压力为999千帕。","Foreign",null,0,0,24,"h3")),List.of(List.of(1f,0f,0f,0f)));
            index.verify(who,document,2,1);var scope=new Scope(who,Map.of(document,2));
            assertThat(index.dense(scope,List.of(1f,0f,0f,0f),30)).extracting(Hit::chunkId).containsExactly(id);
            assertThat(index.keyword(scope,"ZX-204 阀门压力",30)).extracting(Hit::chunkId).containsExactly(id);
            assertThat(index.dense(new Scope(who,Map.of(document,2),"wrong-space"),List.of(1f,0f,0f,0f),30)).isEmpty();
            index.upsert(who,List.of(new Chunk(id,document,2,"阀门 ZX-204 允许压力为97千帕。","Valve",null,0,0,24,"h")),List.of(List.of(1f,0f,0f,0f)));index.verify(who,document,2,1);
            index.remove(who,document);assertThat(index.keyword(scope,"阀门",30)).isEmpty();
            assertThat(index.keyword(new Scope(other,Map.of(document,2)),"阀门",30)).hasSize(1);
        }finally{index.close();var client=new MilvusClientV2(ConnectConfig.builder().uri(uri).build());try{client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());}finally{client.close();}}
    }
}
