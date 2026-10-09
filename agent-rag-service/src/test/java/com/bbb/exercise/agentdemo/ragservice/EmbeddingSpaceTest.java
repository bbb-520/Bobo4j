package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EmbeddingSpaceTest {
    JdbcTemplate db;RagRepository repository;ChatIdentity who=new ChatIdentity("t","u",true);
    String first="a".repeat(64),second="b".repeat(64);
    @BeforeEach void initialize()throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(source);
        for(Path file:Files.list(Path.of("src/main/resources/db/migration")).sorted().toList())for(String sql:Files.readString(file).split(";"))if(!sql.isBlank())db.execute(sql);
        repository=new RagRepository(db,new DataSourceTransactionManager(source));
    }
    @Test void changedModelCannotMixOneIndexAndRebuildPreservesOldReadyVersionUntilReadback() {
        String id=repository.register(who,"req","doc","hash","/test").documentId();Job old=repository.claim().orElseThrow();repository.bindEmbedding(old,first);repository.bindEmbedding(old,first);
        assertThatThrownBy(()->repository.bindEmbedding(old,second)).hasMessage("EMBEDDING_REBUILD_REQUIRED");
        var chunk=new Chunk("1".repeat(64),id,1,"body","heading",null,0,0,4,"2".repeat(64));repository.saveChunks(old,List.of(chunk));repository.indexed(old,List.of(chunk));repository.indexReady(old);
        var conversation=repository.createConversation(who,List.of(id));repository.assertEmbedding(repository.scope(who,conversation.conversationId()),first);
        assertThatThrownBy(()->repository.assertEmbedding(repository.scope(who,conversation.conversationId()),second)).hasMessage("EMBEDDING_REBUILD_REQUIRED");
        assertThat(repository.chunks(who,id,1).getFirst().embeddingFingerprint()).isEqualTo(first);
        repository.park(old,"FAILED","EMBEDDING_REBUILD_REQUIRED");repository.retry(who,id);assertThat(repository.document(who,id).indexVersion()).isEqualTo(1);
        Job rebuilt=repository.claim().orElseThrow();assertThat(rebuilt.version()).isEqualTo(2);repository.bindEmbedding(rebuilt,second);
        repository.assertEmbedding(repository.scope(who,conversation.conversationId()),first);
        var newChunk=new Chunk("3".repeat(64),id,2,"body","heading",null,0,0,4,"4".repeat(64));repository.saveChunks(rebuilt,List.of(newChunk));repository.indexed(rebuilt,List.of(newChunk));repository.indexReady(rebuilt);
        assertThatThrownBy(()->repository.scope(who,conversation.conversationId())).hasMessage("DOCUMENT_VERSION_CHANGED");
    }
}
