package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RagRepositoryTest {
    JdbcTemplate db;RagRepository repository;ChatIdentity owner=new ChatIdentity("t","u",true),other=new ChatIdentity("t","v",true);
    @BeforeEach void initialize()throws Exception{var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(source);for(String file:List.of("V1__rag_baseline.sql","V2__retrieval_snapshots.sql","V3__composite_token_budgets.sql","V4__child_attempt_reservations.sql","V5__embedding_spaces.sql")){String schema=Files.readString(Path.of("src/main/resources/db/migration/"+file));for(String sql:schema.split(";"))if(!sql.isBlank())db.execute(sql);}repository=new RagRepository(db,new DataSourceTransactionManager(source));}
    String document(){return repository.register(owner,"req","report.txt","hash","/test").documentId();}
    @Test void duplicateRequestReusesOnlySameOwnerAndContent(){String id=document();assertThat(repository.register(owner,"req","x","hash","/other").documentId()).isEqualTo(id);assertThatThrownBy(()->repository.register(owner,"req","x","different","/other")).hasMessage("IDEMPOTENCY_CONFLICT");assertThat(repository.register(other,"req","x","different","/other").documentId()).isNotEqualTo(id);assertThatThrownBy(()->repository.document(other,id)).hasMessage("DOCUMENT_OR_CONVERSATION_NOT_FOUND");}
    @Test void staleWorkerCannotPublishIndexAfterLeaseTakeover(){String id=document();Job old=repository.claim().orElseThrow();db.update("UPDATE rag_job SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE document_id=?",id);Job current=repository.claim().orElseThrow();assertThat(current.fence()).isGreaterThan(old.fence());assertThatThrownBy(()->repository.saveChunks(old,List.of())).hasMessage("STALE_JOB_LEASE");repository.stage(current,"CHUNKING");}
    @Test void summaryFailureKeepsUsableIndexAndPinnedConversationRejectsVersionChange(){String id=document();Job job=repository.claim().orElseThrow();repository.saveChunks(job,List.of(new Chunk("a".repeat(64),id,1,"Budget97","Budget",null,0,0,8,"b".repeat(64))));repository.indexed(job,repository.chunks(owner,id,1));repository.indexReady(job);String conversation=repository.createConversation(owner,List.of(id)).conversationId();repository.park(job,"FAILED","SUMMARY_FAILED");assertThat(repository.document(owner,id).indexStatus()).isEqualTo("READY");assertThat(repository.scope(owner,conversation).versions()).containsEntry(id,1);db.update("UPDATE rag_document SET index_version=2 WHERE document_id=?",id);assertThatThrownBy(()->repository.scope(owner,conversation)).hasMessage("DOCUMENT_VERSION_CHANGED");}
    @Test void answerCallIdCommitsHistoryOnceAndRejectsChangedQuestion(){String id=document();Job job=repository.claim().orElseThrow();repository.indexReady(job);String conversation=repository.createConversation(owner,List.of(id)).conversationId();var request=new AnswerRequest("call",conversation,"Q?");var lease=repository.begin(owner,request);var answer=new Answer("no evidence",List.of(),"NO_EVIDENCE",false,10,1);repository.commit(owner,request,lease.fence(),answer);assertThat(repository.begin(owner,request).completed()).isEqualTo(answer);assertThat(repository.history(owner,conversation)).hasSize(1);assertThatThrownBy(()->repository.begin(owner,new AnswerRequest("call",conversation,"Changed"))).hasMessage("CALL_ID_PARAMETER_CONFLICT");}
    @Test void answerLeaseRenewsOnlyForLiveOwnerAndPinnedDocument() {
        String id=document();repository.indexReady(repository.claim().orElseThrow());
        String conversation=repository.createConversation(owner,List.of(id)).conversationId();
        var request=new AnswerRequest("long-answer",conversation,"Q?");var old=repository.begin(owner,request);
        db.update("UPDATE rag_answer SET lease_until=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(6)) WHERE call_id='long-answer'");
        repository.renewAnswer(owner,request,old.fence());
        assertThat(db.queryForObject("SELECT CASE WHEN lease_until>TIMESTAMPADD(SECOND,240,CURRENT_TIMESTAMP(6)) THEN 1 ELSE 0 END FROM rag_answer WHERE call_id='long-answer'",Integer.class)).isEqualTo(1);
        db.update("UPDATE rag_answer SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE call_id='long-answer'");
        assertThatThrownBy(()->repository.renewAnswer(owner,request,old.fence())).hasMessage("STALE_ANSWER_LEASE");
        var current=repository.begin(owner,request);
        assertThatThrownBy(()->repository.renewAnswer(owner,request,old.fence())).hasMessage("STALE_ANSWER_LEASE");
        repository.renewAnswer(owner,request,current.fence());
        db.update("UPDATE rag_document SET deleted=TRUE WHERE document_id=?",id);
        assertThatThrownBy(()->repository.renewAnswer(owner,request,current.fence())).hasMessage("DOCUMENT_OR_CONVERSATION_NOT_FOUND");
        assertThat(repository.history(owner,conversation)).isEmpty();
    }
}
