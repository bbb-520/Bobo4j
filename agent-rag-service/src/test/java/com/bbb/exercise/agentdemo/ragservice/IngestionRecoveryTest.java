package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.processing.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.storage.DocumentStorage;
import com.bbb.exercise.agentdemo.ragservice.retrieval.VectorIndex;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class IngestionRecoveryTest {
 @TempDir Path dir;
 @Test void wholeDocumentSummaryCoversTailAndResumesSavedNodesWithoutExtraPaidCalls()throws Exception{
    var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");var db=new JdbcTemplate(source);for(String file:List.of("V1__rag_baseline.sql","V2__retrieval_snapshots.sql","V3__composite_token_budgets.sql","V4__child_attempt_reservations.sql","V5__embedding_spaces.sql"))for(String sql:Files.readString(Path.of("src/main/resources/db/migration/"+file)).split(";"))if(!sql.isBlank())db.execute(sql);var repository=new RagRepository(db,new DataSourceTransactionManager(source));
    Path original=dir.resolve("original.html");var body=new StringBuilder("<html><body><h1>Long budget</h1>");for(int i=0;i<20;i++)body.append("<p>Section ").append(i).append(" background facts ").append("detail ".repeat(300)).append(".</p>");body.append("<h2>Last</h2><p>TAIL_VALUE_97</p></body></html>");Files.writeString(original,body);
    var who=new ChatIdentity("t","u",true);String id=repository.register(who,"req","long.html","hash",original.toString()).documentId();var gateway=new FakeGateway();var index=new MemoryIndex();var worker=new IngestionWorker(repository,new DocumentStorage(dir.toString(),repository),index,gateway,2,200000,new SimpleMeterRegistry());boolean sawIndependentReady=false;
    for(int i=0;i<80;i++){db.update("UPDATE rag_job SET available_at=CURRENT_TIMESTAMP");var job=repository.claim();if(job.isEmpty())break;worker.process(job.get());var document=repository.document(who,id);if(document.indexStatus().equals("READY")&&!document.summaryStatus().equals("READY")){sawIndependentReady=true;assertThat(repository.createConversation(who,List.of(id)).indexVersions()).containsEntry(id,1);}}
    assertThat(sawIndependentReady).isTrue();var summary=repository.summary(who,id);assertThat(summary.status()).isEqualTo("READY");assertThat(summary.coverage()).isEqualTo(1);assertThat(summary.text()).contains("TAIL_VALUE_97");assertThat(gateway.calls.values()).allMatch(n->n==1);assertThat(repository.document(who,id).chunkCount()).isGreaterThan(8);
 }
 static class FakeGateway extends RagModelGateway {
    final Map<String,Integer> calls=new HashMap<>();FakeGateway(){super(null,null,null);}
    @Override public String embeddingFingerprint(ChatIdentity who,int dimensions){return "f".repeat(64);}
    @Override public EmbeddingBatch embed(ChatIdentity who,String composite,String call,List<String> texts,int dimensions){calls.merge(call,1,Integer::sum);return new EmbeddingBatch(texts.stream().map(x->List.of(1f,0f)).toList(),texts.size()*7,"f".repeat(64));}
    @Override public Completion complete(ChatIdentity who,String composite,String call,String mode,String system,String user,boolean fallback){calls.merge(call,1,Integer::sum);return new Completion(user.contains("TAIL_VALUE_97")?"summary TAIL_VALUE_97":"summary background",13,5,"fake-protocol",false);}
 }
 static class MemoryIndex implements VectorIndex {
    final Set<String> ids=new HashSet<>();public void upsert(ChatIdentity who,List<Chunk> chunks,List<List<Float>> vectors){chunks.forEach(c->ids.add(c.id()));}public void verify(ChatIdentity who,String document,int version,int expected){assertThat(ids).hasSize(expected);}public List<Hit> dense(Scope scope,List<Float> vector,int limit){throw new UnsupportedOperationException();}public List<Hit> keyword(Scope scope,String query,int limit){throw new UnsupportedOperationException();}public void remove(ChatIdentity who,String document){ids.clear();}
 }
}
