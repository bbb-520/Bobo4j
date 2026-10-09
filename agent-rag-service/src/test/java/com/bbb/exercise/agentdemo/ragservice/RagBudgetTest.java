package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.GroupState;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.runtime.client.BillingClient;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RagBudgetTest {
    JdbcTemplate db;FakeBilling billing;FakeModels models;RagModelGateway gateway;
    ChatIdentity who=new ChatIdentity("tenant","user",true);
    @BeforeEach void initialize()throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(source);
        for(String file:List.of("V1__rag_baseline.sql","V2__retrieval_snapshots.sql","V3__composite_token_budgets.sql","V4__child_attempt_reservations.sql","V5__embedding_spaces.sql"))for(String sql:Files.readString(Path.of("src/main/resources/db/migration/"+file)).split(";"))if(!sql.isBlank())db.execute(sql);
        billing=new FakeBilling();models=new FakeModels();gateway=new RagModelGateway(models,billing,db);
    }
    @Test void allStagesShareHardBudgetAndReceiptReplayDoesNotChargeAgain() {
        gateway.ensureBudget(who,"execution",3000L);
        gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false);
        gateway.embed(who,"execution","query",List.of("Q"),64);
        assertThat(gateway.budget(who,"execution").actual()).isEqualTo(1012);
        assertThatThrownBy(()->gateway.complete(who,"execution","judge","EVALUATION","judge","facts",false)).hasMessage("MODEL_TOKEN_BUDGET");
        assertThat(models.calls).isEqualTo(2);
        gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false);
        assertThat(models.calls).isEqualTo(2);assertThat(gateway.budget(who,"execution").actual()).isEqualTo(1012);
        assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Changed",false)).hasMessage("MODEL_CALL_PARAMETER_CONFLICT");
        gateway.ensureBudget(who,"execution",64000L);assertThat(gateway.budget(who,"execution").limit()).isEqualTo(3000);
    }
    @Test void primaryCanRunWhenRemainingBudgetCannotAlsoReserveFallback() {
        long primary=TokenBudgetEstimator.completion("DOCUMENT_QA","rules","Q",false);
        gateway.ensureBudget(who,"small",primary);
        gateway.complete(who,"small","draft","DOCUMENT_QA","rules","Q",true);
        assertThat(models.lastLimit).isEqualTo(primary);
        assertThat(models.calls).isEqualTo(1);
        assertThat(gateway.budget(who,"small").actual()).isEqualTo(1000);
        assertThat(gateway.budget(who,"small").held()).isZero();
    }
    @Test void genuinelyUnknownHoldsUpperUntilMeasuredReconciliationThenNewGenerationIsReserved() {
        long estimate=TokenBudgetEstimator.completion("DOCUMENT_QA","rules","Q",false);
        gateway.ensureBudget(who,"execution",estimate+150L);models.fail=true;
        billing.groups.put("draft",new GroupState("draft","UNKNOWN",null,null,1,0,0,estimate));
        assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(gateway.status(who,"execution")).containsEntry("status","UNKNOWN").containsEntry("unknownTokens",estimate);
        assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(models.calls).isEqualTo(1);
        billing.groups.put("draft",new GroupState("draft","FAILED_RESOLVED",null,null,1,25,5,0));
        assertThat(gateway.status(who,"execution")).containsEntry("status","FAILED_RESOLVED").containsEntry("inputTokens",25L).containsEntry("outputTokens",5L).containsEntry("unknownTokens",0L);
        models.fail=false;gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false);
        assertThat(models.calls).isEqualTo(2);assertThat(gateway.budget(who,"execution").actual()).isEqualTo(1030);
    }
    @Test void resolvedFeeWithoutKnownUsageRetainsUpperAndCannotFundAnotherAttempt() {
        long estimate=TokenBudgetEstimator.completion("DOCUMENT_QA","rules","Q",false);
        gateway.ensureBudget(who,"execution",estimate+100L);models.fail=true;
        billing.groups.put("draft",new GroupState("draft","UNKNOWN",null,null,1,0,0,estimate));
        assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        billing.groups.put("draft",new GroupState("draft","FAILED_RESOLVED",null,null,1,0,0,estimate));
        assertThat(gateway.status(who,"execution")).containsEntry("status","FAILED_RESOLVED").containsEntry("unknownTokens",estimate);
        models.fail=false;assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false)).hasMessage("MODEL_TOKEN_BUDGET");assertThat(models.calls).isEqualTo(1);
    }
    @Test void neverDispatchedFailureReleasesHoldAndOwnerCannotReadAnotherComposite() {
        gateway.ensureBudget(who,"execution",3000L);models.fail=true;
        assertThatThrownBy(()->gateway.complete(who,"execution","draft","DOCUMENT_QA","rules","Q",false)).isInstanceOf(ModelCallClient.ModelCallException.class);
        assertThat(gateway.status(who,"execution")).containsEntry("status","FAILED_RESOLVED").containsEntry("unknownTokens",0L);
        assertThat(gateway.budget(who,"execution").held()).isZero();
        assertThatThrownBy(()->gateway.status(new ChatIdentity("tenant","someoneElse",true),"execution")).hasMessage("RAG_CALL_NOT_FOUND");
    }
    @Test void resumedEmbeddingReceiptRetainsOlderUnknownGenerationAfterCrash() {
        gateway.ensureBudget(who,"execution",1000L);
        long estimate=TokenBudgetEstimator.embedding(List.of("Q"));
        String hash=com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker.hash(new com.google.gson.Gson().toJson(List.of(List.of("Q"),64)));
        db.update("INSERT INTO rag_model_call(tenant_id,user_id,call_id,composite_id,request_hash,capability,status,reserved_tokens,attempt_reservation) VALUES('tenant','user','query','execution',?,'EMBEDDING','PENDING',?,?)",hash,400+estimate,estimate);
        db.update("UPDATE rag_call_budget SET held_tokens=? WHERE composite_id='execution'",400+estimate);
        gateway.embed(who,"execution","query",List.of("Q"),64);
        assertThat(gateway.budget(who,"execution").actual()).isEqualTo(12);assertThat(gateway.budget(who,"execution").held()).isEqualTo(400);
        gateway.embed(who,"execution","query",List.of("Q"),64);assertThat(models.calls).isEqualTo(1);
        gateway.ensureBudget(who,"execution",50L);assertThat(gateway.budget(who,"execution").limit()).isEqualTo(462);
    }
    static class FakeModels extends ModelCallClient {
        int calls;boolean fail;Long lastLimit;FakeModels(){super(null,null,WebClient.builder());}
        @Override public Completion complete(ChatIdentity who,String call,String mode,String system,String user,boolean fallback,Long limit){lastLimit=limit;return complete(who,call,mode,system,user,fallback);}
        @Override public EmbeddingBatch embed(ChatIdentity who,String call,List<String> texts,int dimensions,Long limit){return embed(who,call,texts,dimensions);}
        @Override public Completion complete(ChatIdentity who,String call,String mode,String system,String user,boolean fallback){calls++;if(fail)throw new ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);return new Completion("answer",900,100,"fake-protocol",false);}
        @Override public EmbeddingBatch embed(ChatIdentity who,String call,List<String> texts,int dimensions){calls++;return new EmbeddingBatch(List.of(Collections.nCopies(dimensions,1f)),12);}
    }
    static class FakeBilling extends BillingClient {
        Map<String,GroupState> groups=new HashMap<>();FakeBilling(){super(null);}
        @Override public Mono<GroupState> group(ChatIdentity who,String call){return groups.containsKey(call)?Mono.just(groups.get(call)):Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));}
    }
}
