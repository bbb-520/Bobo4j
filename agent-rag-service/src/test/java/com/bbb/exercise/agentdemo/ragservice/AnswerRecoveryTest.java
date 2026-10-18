package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.answer.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings={"EVALUATION_FORMAT", "PROVIDER_REJECTED", "MODEL_OUTCOME_UNKNOWN"})
    void knownAnswerFailureEndsWhileUnknownModelUsageRemainsAuditable(String code) {
        var who=new ChatIdentity("t","u",true);
        var request=new AnswerRequest("call","conversation","question",64000L);
        var repository=mock(RagRepository.class);var models=mock(RagModelGateway.class);
        var retriever=mock(HybridRetriever.class);var evaluator=mock(GroundingEvaluator.class);
        when(repository.begin(who,request)).thenReturn(new RagRepository.AnswerLease(1,null));
        when(retriever.retrieve(eq(who),any(),anyString())).thenReturn(new Retrieval("question",List.of(new Evidence("c","d",1,"fact","title",null,0,1)),false));
        var generation=when(models.complete(eq(who),eq("call"),anyString(),eq("DOCUMENT_QA"),anyString(),anyString(),eq(true)));
        if(code.equals("EVALUATION_FORMAT")) {
            generation.thenReturn(new Completion("fact [S1]",2,2,"fixture",false));
            when(evaluator.evaluate(eq(who),eq("call"),anyString(),eq("question"),anyList(),anyString()))
                    .thenReturn(new GroundingEvaluator.Evaluation("ERROR","EVALUATION_UNAVAILABLE",2,2));
        } else generation.thenThrow(new ModelCallClient.ModelCallException(code,false,503));
        var service=new DocumentAnswerService(repository,retriever,models,evaluator);
        try {assertThatThrownBy(()->service.answer(who,request)).satisfies(error->{
            if(code.equals("EVALUATION_FORMAT"))assertThat(error).hasMessageContaining("ANSWER_EVALUATION_UNAVAILABLE");
            else assertThat(((ModelCallClient.ModelCallException)error).code()).isEqualTo(code);
        });}
        finally {service.shutdown();}
        verify(repository).answerError(who,request,1,code.equals("MODEL_OUTCOME_UNKNOWN")?"WAITING_FOR_RECONCILIATION":"FAILED");
        verify(repository,never()).commit(any(),any(),anyLong(),any());
    }
}
