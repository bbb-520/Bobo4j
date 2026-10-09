package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.answer.GroundingEvaluator;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.Evidence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class GroundingEvaluatorTest {
    FakeGateway models=new FakeGateway();ChatIdentity who=new ChatIdentity("t","u",true);
    List<Evidence> evidence=List.of(new Evidence("c","d",1,"Budget is97.","Budget",null,1,.9));
    @Test void actualSpringAiRelevancyEvaluatorAndFactJudgeMustBothPass(){
        models.responses.put("eval:relevance",new Completion("YES",11,1,"judge",false));
        models.responses.put("eval:facts",new Completion("{\"supported\":true,\"unsupportedClaims\":[]}",15,4,"judge",false));
        var result=new GroundingEvaluator(models,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).evaluate(who,"root","eval","What is budget?",evidence,"Budget is97 [S1].");assertThat(result.status()).isEqualTo("PASS");assertThat(result.inputTokens()).isEqualTo(26);assertThat(result.outputTokens()).isEqualTo(5);
    }
    @Test void malformedJudgeResultIsErrorNeverPass(){models.responses.put("eval:relevance",new Completion("MAYBE",11,1,"judge",false));assertThat(new GroundingEvaluator(models,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).evaluate(who,"root","eval","Budget?",evidence,"Budget is97 [S1].").status()).isEqualTo("ERROR");}
    @Test void relevantButUnsupportedNumbersFail(){models.responses.put("eval:relevance",new Completion("YES",11,1,"judge",false));models.responses.put("eval:facts",new Completion("{\"supported\":false,\"unsupportedClaims\":[\"Budget98\"]}",15,4,"judge",false));assertThat(new GroundingEvaluator(models,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).evaluate(who,"root","eval","Budget?",evidence,"Budget is98 [S1].").status()).isEqualTo("FAIL");}
    static class FakeGateway extends RagModelGateway {
        final Map<String,Completion> responses=new HashMap<>();
        FakeGateway(){super(null,null,null);}
        @Override public Completion complete(ChatIdentity identity,String composite,String call,String mode,String system,String user,boolean fallback){
            if(!composite.equals("root")||!mode.equals("EVALUATION")||fallback)throw new AssertionError("Judge call scope/policy changed");
            Completion response=responses.get(call);if(response==null)throw new AssertionError("Unexpected paid call "+call);return response;
        }
    }
}
