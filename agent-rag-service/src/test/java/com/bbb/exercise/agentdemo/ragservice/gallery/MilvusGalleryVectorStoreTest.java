package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.common.DataType;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MilvusGalleryVectorStoreTest {
    private final MilvusClientV2 client=mock(MilvusClientV2.class);
    private final EmbeddingModel embedding=mock(EmbeddingModel.class);
    private final GalleryProperties properties=new GalleryProperties();
    private MilvusGalleryVectorStore store() {
        properties.setDimensions(3);
        return new MilvusGalleryVectorStore(properties,()->embedding,()->client);
    }
    @Test void embedsOnlyTextAndUpsertsStableIdsWithJsonArrayMetadata() {
        var store=store();schemaReady(store);
        when(embedding.embed(List.of("Draw a tree."))).thenReturn(List.of(new float[]{1,0,0}));
        var doc=new Document("stable-id","Draw a tree.",Map.of("category","art","tags",List.of("tree"),"image_urls",List.of("docs/tree.png")));
        store.add(List.of(doc));store.add(List.of(doc));
        var req=ArgumentCaptor.forClass(UpsertReq.class);verify(client,times(2)).upsert(req.capture());
        assertThat(req.getValue().getCollectionName()).startsWith("agent_prompt_gallery_");
        var row=req.getValue().getData().getFirst();
        assertThat(row.get("id").getAsString()).isEqualTo("stable-id");
        assertThat(row.getAsJsonObject("metadata").getAsJsonArray("tags").get(0).getAsString()).isEqualTo("tree");
        verify(embedding,times(2)).embed(List.of("Draw a tree."));
        verify(embedding,never()).dimensions();
    }
    @Test void parameterizesLiteralFilterAndMapsPersistentSearchResults() {
        var store=store();schemaReady(store);
        when(embedding.embed("tree")).thenReturn(new float[]{1,0,0});
        when(client.search(any(SearchReq.class))).thenReturn(SearchResp.builder().searchResults(List.of(List.of(
                SearchResp.SearchResult.builder().id("id-1").score(.9f).entity(Map.of("content","Draw a tree.","metadata",Map.of("category","art","tags",List.of("tree")))).build()))).build());
        String value="art\" or true";
        var docs=store.similaritySearch(SearchRequest.builder().query("tree").topK(3).filterExpression(new FilterExpressionBuilder().eq("category",value).build()).build());
        var request=ArgumentCaptor.forClass(SearchReq.class);verify(client).search(request.capture());
        assertThat(request.getValue().getFilter()).doesNotContain(value).contains("{gallery_filter_0}");
        assertThat(request.getValue().getFilterTemplateValues()).containsEntry("gallery_filter_0",value);
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getText()).isEqualTo("Draw a tree.");
        assertThat(docs.getFirst().getScore()).isCloseTo(.9,within(.0001));
    }
    @Test void validatesEmbeddingVectorsBeforeWritingAndRejectsUserRagCollection() {
        properties.setCollection("agent_rag_v2");
        assertThatIllegalArgumentException().isThrownBy(this::store);
        properties.setCollection("agent_prompt_gallery_test");var store=store();schemaReady(store);
        when(embedding.embed(List.of("tree"))).thenReturn(List.of(new float[]{Float.NaN,0,0}));
        assertThatIllegalStateException().isThrownBy(()->store.add(List.of(new Document("tree"))));
        verify(client,never()).upsert(any());
    }
    @Test void rejectsDifferentEmbeddingSpaceEvenWithMatchingDimensions() {
        var store=store();schemaReady(store);
        var described=client.describeCollection(DescribeCollectionReq.builder().build());
        described.setDescription("another-space");
        assertThatIllegalStateException().isThrownBy(()->store.add(List.of(new Document("tree")))).withMessageContaining("embedding space");
        verifyNoInteractions(embedding);
    }
    @Test void rejectsZeroEmbeddingVectorBeforeCosineIndexWrite() {
        var store=store();schemaReady(store);
        when(embedding.embed(List.of("tree"))).thenReturn(List.of(new float[]{0,0,0}));
        assertThatIllegalStateException().isThrownBy(()->store.add(List.of(new Document("tree")))).withMessageContaining("zero");
        verify(client,never()).upsert(any());
    }
    private void schemaReady(MilvusGalleryVectorStore store) {
        when(client.hasCollection(any())).thenReturn(true);
        var schema=CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(3).build());
        schema.addField(AddFieldReq.builder().fieldName("metadata").dataType(DataType.JSON).build());
        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(65535).build());
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).build());
        when(client.describeCollection(any())).thenReturn(DescribeCollectionResp.builder().description(store.schemaDescription()).collectionSchema(schema).fieldNames(List.of("id","content","metadata","embedding")).build());
    }
}
