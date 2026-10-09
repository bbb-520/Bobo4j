package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.answer.*;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LongChineseAnswerTest {
    @Test void twentyLongCandidatesKeepTailFactsAndFinishAllJudgesWithin64000() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        for(Path migration:Files.list(Path.of("src/main/resources/db/migration")).sorted().toList())
            for(String sql:Files.readString(migration).split(";"))if(!sql.isBlank())db.execute(sql);
        var repository=new RagRepository(db,new DataSourceTransactionManager(source));
        var who=new ChatIdentity("t","u",true);String fingerprint="f".repeat(64);
        String document=repository.register(who,"upload","large.txt","hash","/test").documentId();
        var job=repository.claim().orElseThrow();repository.bindEmbedding(job,fingerprint);
        List<Chunk> chunks=new ArrayList<>();
        for(int i=0;i<20;i++){
            String text="无关背景".repeat(500)+"\nZX-204 尾部预算为97万元。章节"+i;
            chunks.add(new Chunk(SemanticChunker.hash("chunk"+i),document,1,text,"章节"+i,null,i,i*2200,i*2200+text.length(),SemanticChunker.hash(text)));
        }
        repository.saveChunks(job,chunks);repository.indexed(job,repository.chunks(who,document,1));repository.indexReady(job);
        String conversation=repository.createConversation(who,List.of(document)).conversationId();
        var protocol=new BoundedModels();
        var gateway=new RagModelGateway(protocol,null,db){@Override public String embeddingFingerprint(ChatIdentity identity,int dimensions){return fingerprint;}};
        var index=new VectorIndex(){
            public void upsert(ChatIdentity identity,List<Chunk> body,List<List<Float>> vectors){}
            public void verify(ChatIdentity identity,String doc,int version,int count){}
            public void remove(ChatIdentity identity,String doc){}
            public List<Hit> dense(Scope scope,List<Float> vector,int limit){return hits();}
            public List<Hit> keyword(Scope scope,String query,int limit){return hits();}
            private List<Hit> hits(){return chunks.stream().map(c->new Hit(c.id(),document,1,"t","u",1)).toList();}
        };
        var metrics=new SimpleMeterRegistry();
        var answers=new DocumentAnswerService(repository,new HybridRetriever(index,gateway,repository,64,.25,metrics),gateway,new GroundingEvaluator(gateway,metrics));
        Answer answer=answers.answer(who,new AnswerRequest("qa",conversation,"ZX-204 的尾部预算是多少？",64000L));
        assertThat(answer.evaluation()).isEqualTo("PASS");
        assertThat(answer.sources()).isNotEmpty().allSatisfy(s->assertThat(s.excerpt()).contains("97万元"));
        assertThat(protocol.ranked).hasSize(20).allSatisfy(text->{assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(768);assertThat(text).contains("97万元");});
        assertThat(gateway.budget(who,"qa").actual()).isLessThanOrEqualTo(64000);
        assertThat(gateway.budget(who,"qa").held()).isZero();
        assertThat(repository.history(who,conversation)).hasSize(1);
        int calls=protocol.calls;answers.answer(who,new AnswerRequest("qa",conversation,"ZX-204 的尾部预算是多少？",0L));
        assertThat(protocol.calls).isEqualTo(calls);
    }
    static class BoundedModels extends ModelCallClient {
        List<String> ranked;int calls;
        BoundedModels(){super(null,null,WebClient.builder());}
        @Override public EmbeddingBatch embed(ChatIdentity who,String call,List<String> texts,int dimensions,Long budget){
            calls++;long bound=TokenBudgetEstimator.embedding(texts);assertThat(budget).isGreaterThanOrEqualTo(bound);
            return new EmbeddingBatch(List.of(Collections.nCopies(dimensions,1f)),bound,"f".repeat(64));
        }
        @Override public Ranking rerank(ChatIdentity who,String call,String query,List<String> docs,Long budget){
            calls++;ranked=docs;long bound=TokenBudgetEstimator.rerank(query,docs);assertThat(budget).isGreaterThanOrEqualTo(bound);
            return new Ranking(java.util.stream.IntStream.range(0,docs.size()).mapToObj(i->new Ranked(i,.99-i*.01)).toList(),bound);
        }
        @Override public Completion complete(ChatIdentity who,String call,String mode,String system,String user,boolean fallback,Long budget){
            calls++;long bound=TokenBudgetEstimator.completion(mode,system,user,false);assertThat(budget).isGreaterThanOrEqualTo(bound);
            String answer=call.endsWith(":relevance")?"YES":call.endsWith(":facts")?"{\"supported\":true,\"unsupportedClaims\":[]}":"ZX-204 的尾部预算是97万元。[S1]";
            return new Completion(answer,bound-2048,20,"protocol",false);
        }
    }
}
