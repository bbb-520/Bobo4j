package com.bbb.exercise.agentdemo.ragservice.gallery;

import io.milvus.v2.client.*;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.QueryReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real Milvus persistence; deterministic local embedding means no paid provider request. */
@EnabledIfEnvironmentVariable(named="PROMPT_GALLERY_MILVUS_INTEGRATION",matches="true")
class GalleryMilvusIntegrationTest {
    @Test void realUpsertFiltersJsonMetadataAndDeletesWithoutPollutingUserRag() {
        var properties=new GalleryProperties();properties.setDimensions(3);properties.setEmbeddingSpace("test/deterministic/v1");
        properties.setCollection("agent_prompt_gallery_it_"+UUID.randomUUID().toString().replace("-",""));
        properties.setMilvusUri(System.getenv().getOrDefault("MILVUS_URI","http://127.0.0.1:19530"));
        properties.setMilvusToken(System.getenv().getOrDefault("MILVUS_TOKEN",""));
        EmbeddingModel model=new EmbeddingModel() {
            @Override public float[] embed(Document document) { return embed(document.getText()); }
            @Override public EmbeddingResponse call(EmbeddingRequest request) {
                var embeddings=new ArrayList<Embedding>();
                for(int i=0;i<request.getInstructions().size();i++) embeddings.add(new Embedding(request.getInstructions().get(i).contains("ocean")?new float[]{0,1,0}:new float[]{1,0,0},i));
                return new EmbeddingResponse(embeddings);
            }
        };
        var store=new MilvusGalleryVectorStore(properties,()->model);
        var admin=new MilvusClientV2(ConnectConfig.builder().uri(properties.getMilvusUri()).token(properties.getMilvusToken()).build());
        try {
            var tree=new Document("tree-id","Draw a tree.",Map.of("category","art","tags",List.of("tree","paint"),"image_urls",List.of("docs/tree.png")));
            var ocean=new Document("ocean-id","Draw an ocean.",Map.of("category","photo","tags",List.of("sea")));
            store.add(List.of(tree,ocean));store.add(List.of(tree));
            var count=admin.query(QueryReq.builder().collectionName(properties.getCollection()).filter("id != \"\"").outputFields(List.of("count(*)")).consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG).build());
            assertThat(((Number)count.getQueryResults().getFirst().getEntity().get("count(*)")).intValue()).isEqualTo(2);
            var service=new PromptGalleryService(store,new GalleryMarkdownParser(),properties);
            var hits=service.searchPrompts("tree",5,"art");
            assertThat(hits).extracting(Document::getId).containsExactly("tree-id");
            assertThat(hits.getFirst().getMetadata().get("tags")).isEqualTo(List.of("tree","paint"));
            assertThat(service.searchPrompts("tree",5,"art\" or true")).isEmpty();
            store.delete(List.of("tree-id"));assertThat(service.searchPrompts("tree",5,"art")).isEmpty();
            store.delete(new FilterExpressionBuilder().eq("category","photo").build());
            assertThat(store.similaritySearch(SearchRequest.builder().query("ocean").topK(5).build())).isEmpty();
        } finally {
            store.close();
            if(admin.hasCollection(HasCollectionReq.builder().collectionName(properties.getCollection()).build()))
                admin.dropCollection(DropCollectionReq.builder().collectionName(properties.getCollection()).build());
            admin.close();
        }
    }
}
