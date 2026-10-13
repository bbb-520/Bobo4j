package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.Filter;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptGalleryServiceTest {
    @Test void searchesRealVectorStoreContractWithLiteralCategoryFilter() {
        VectorStore store=mock(VectorStore.class);
        var expected=List.of(new Document("paint"));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(expected);
        var service=new PromptGalleryService(store,new GalleryMarkdownParser(),new GalleryProperties());
        String category="art\" or category != \"";
        assertThat(service.searchPrompts("  paint a mountain  ",5,category)).isEqualTo(expected);
        var request=ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().getQuery()).isEqualTo("paint a mountain");
        assertThat(request.getValue().getTopK()).isEqualTo(5);
        assertThat(request.getValue().getFilterExpression().type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(((Filter.Value)request.getValue().getFilterExpression().right()).value()).isEqualTo(category);
    }
    @Test void rejectsUnboundedQueriesBeforeCallingProvider() {
        VectorStore store=mock(VectorStore.class);
        var service=new PromptGalleryService(store,new GalleryMarkdownParser(),new GalleryProperties());
        for(int topK:new int[]{0,-1,21,Integer.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(()->service.searchPrompts("paint",topK,null));
        assertThatIllegalArgumentException().isThrownBy(()->service.searchPrompts(" ",5,null));
        assertThatIllegalArgumentException().isThrownBy(()->service.searchPrompts("x".repeat(2001),5,null));
        verifyNoInteractions(store);
    }
    @Test void missingCategoryMeansNoMetadataFilter() {
        VectorStore store=mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        new PromptGalleryService(store,new GalleryMarkdownParser(),new GalleryProperties()).searchPrompts("watercolor",1,"  ");
        var request=ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().hasFilterExpression()).isFalse();
    }
}
