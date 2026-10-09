package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.flywaydb.core.Flyway;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="RAG_MYSQL_INTEGRATION",matches="true")
class MySqlIntegrationTest {
 @Test void realMySqlMigrationsLeasesAndAnswerFencing(){
    var source=new DriverManagerDataSource(System.getenv().getOrDefault("RAG_MYSQL_URL","jdbc:mysql://127.0.0.1:13316/rag_verify?allowPublicKeyRetrieval=true&useSSL=false"),"verify","verify-only");var db=new JdbcTemplate(source);
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("rag_verify");
    Flyway.configure().dataSource(source).table("flyway_schema_history_rag").locations("classpath:db/migration").load().migrate();
    var repository=new RagRepository(db,new DataSourceTransactionManager(source));var who=new ChatIdentity("integration",UUID.randomUUID().toString(),true);
    String id=repository.register(who,"req","test.txt","hash","/test").documentId();String conversation=null;
    try{
        var old=repository.claim().orElseThrow();assertThat(old.id()).isEqualTo(id);db.update("UPDATE rag_job SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE document_id=?",id);var current=repository.claim().orElseThrow();assertThat(current.fence()).isGreaterThan(old.fence());assertThatThrownBy(()->repository.stage(old,"INDEXING")).hasMessage("STALE_JOB_LEASE");repository.indexReady(current);
        conversation=repository.createConversation(who,List.of(id)).conversationId();var request=new AnswerRequest("call",conversation,"Q");var lease=repository.begin(who,request);assertThatThrownBy(()->repository.begin(who,request)).hasMessage("ANSWER_IN_PROGRESS");
        db.update("UPDATE rag_answer SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND user_id=? AND call_id='call'",who.tenantId(),who.userId());var takeover=repository.begin(who,request);assertThat(takeover.fence()).isGreaterThan(lease.fence());var answer=new Answer("refusal",List.of(),"NO_EVIDENCE",false,10,1);
        assertThatThrownBy(()->repository.commit(who,request,lease.fence(),answer)).hasMessage("STALE_ANSWER_LEASE");repository.commit(who,request,takeover.fence(),answer);assertThat(repository.begin(who,request).completed()).isEqualTo(answer);assertThat(repository.history(who,conversation)).hasSize(1);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var paid=new RagBudgetTest.FakeModels(){@Override public com.bbb.exercise.agentdemo.api.model.ModelContracts.EmbeddingBatch embed(ChatIdentity owner,String call,List<String> texts,int dimensions){entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test provider timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}return super.embed(owner,call,texts,dimensions);}};
        var gateway=new com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway(paid,new RagBudgetTest.FakeBilling(),db);gateway.ensureBudget(who,"budget",100L);
        var future=java.util.concurrent.CompletableFuture.supplyAsync(()->gateway.embed(who,"budget","query1",List.of("query-token"),64));
        try{assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->gateway.embed(who,"budget","query2",List.of("query-token"),64)).hasMessage("MODEL_TOKEN_BUDGET");}finally{release.countDown();}
        future.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(gateway.budget(who,"budget").actual()).isEqualTo(12);assertThat(gateway.budget(who,"budget").held()).isZero();assertThat(paid.calls).isEqualTo(1);
    }catch(Exception error){throw new AssertionError(error);}finally{db.update("DELETE FROM rag_model_call WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());db.update("DELETE FROM rag_call_budget WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());db.update("DELETE FROM rag_answer WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());db.update("DELETE FROM rag_conversation WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());db.update("DELETE FROM rag_job WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());db.update("DELETE FROM rag_document WHERE tenant_id=? AND user_id=?",who.tenantId(),who.userId());}
 }
}
