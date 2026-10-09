package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class HybridFusionTest {
    Scope scope=new Scope(new ChatIdentity("t","u",true),Map.of("d",3));
    Hit hit(String id,double score){return new Hit(id,"d",3,"t","u",score);}
    @Test void calibratedDenseWeightChangesRankWithoutUsingUncomparableRawScores(){
        assertThat(HybridRetriever.fuse(scope,List.of(hit("semantic",.9)),List.of(hit("number",999)),20,.5)).extracting(Hit::chunkId).containsExactly("number","semantic");
        assertThat(HybridRetriever.fuse(scope,List.of(hit("semantic",.9)),List.of(hit("number",999)),20,1.5)).extracting(Hit::chunkId).containsExactly("semantic","number");
    }
    @Test void semanticAndNumberLanesUseRanksInsteadOfRawScores(){var dense=List.of(hit("semantic",.95),hit("both",.7));var keyword=List.of(hit("number",1500),hit("both",300));assertThat(HybridRetriever.fuse(scope,dense,keyword,20)).extracting(Hit::chunkId).containsExactly("both","number","semantic");}
    @Test void foreignOwnerAndStaleVersionAreRejected(){assertThatThrownBy(()->HybridRetriever.fuse(scope,List.of(new Hit("x","d",3,"t","other",1)),List.of(),20)).hasMessage("RETRIEVAL_SCOPE_VIOLATION");assertThatThrownBy(()->HybridRetriever.fuse(scope,List.of(new Hit("x","d",2,"t","u",1)),List.of(),20)).hasMessage("RETRIEVAL_SCOPE_VIOLATION");}
}
