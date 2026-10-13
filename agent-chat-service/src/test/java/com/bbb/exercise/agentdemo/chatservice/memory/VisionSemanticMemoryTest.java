package com.bbb.exercise.agentdemo.chatservice.memory;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisionSemanticMemoryTest {
    @Test void indexedOlderRowsDoNotStarveReindexingOfNewLegacyMemories() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE vision_memory(id BIGINT PRIMARY KEY,tenant_id VARCHAR,user_id VARCHAR,kind VARCHAR,content VARCHAR,active INT,updated_at TIMESTAMP,embedding_json VARCHAR,embedding_fingerprint VARCHAR,embedding_dimensions INT)");
        var rows=new ArrayList<Object[]>();for(int i=1;i<=1000;i++)rows.add(new Object[]{i});
        db.batchUpdate("INSERT INTO vision_memory VALUES(?,'tenant','owner','SCENE','indexed',1,CURRENT_TIMESTAMP,'[1,0]','v1',2)",rows);
        insert(db,1001,"tenant","owner","new legacy",null,null);
        var model=mock(EmbeddingModel.class);when(model.embed("new legacy")).thenReturn(new float[]{1,0});
        var service=new VisionMemoryService(db,(who,call,budget)->new VisionEmbeddings.Space(model,"v1",2,()->0L),.5);
        assertThat(service.reindex(new ChatIdentity("tenant","owner",true),1)).isEqualTo(1);
    }
    @Test void explicitReindexRebuildsOnlyOwnedLegacyRowsAndIsIdempotent() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE vision_memory(id BIGINT PRIMARY KEY,tenant_id VARCHAR,user_id VARCHAR,kind VARCHAR,content VARCHAR,active INT,updated_at TIMESTAMP,embedding_json VARCHAR,embedding_fingerprint VARCHAR,embedding_dimensions INT)");
        insert(db,1,"tenant","owner","legacy",null,null);insert(db,2,"tenant","victim","private",null,null);insert(db,3,"tenant","owner","corrupt","[1]","v1");
        var calls=new ArrayList<String>();var model=mock(EmbeddingModel.class);when(model.embed("legacy")).thenReturn(new float[]{1,0});when(model.embed("corrupt")).thenReturn(new float[]{1,0});
        VisionEmbeddings embeddings=(who,call,budget)->{calls.add(call);return new VisionEmbeddings.Space(model,"v1",2,()->0L);};
        var service=new VisionMemoryService(db,embeddings,.5);var who=new ChatIdentity("tenant","owner",true);
        assertThat(service.reindex(who,1)).isEqualTo(1);assertThat(service.reindex(who,1)).isEqualTo(1);assertThat(service.reindex(who,1)).isZero();
        verify(model,times(1)).embed("legacy");verify(model,never()).embed("private");
        assertThat(db.queryForObject("SELECT embedding_fingerprint FROM vision_memory WHERE id=1",String.class)).isEqualTo("v1");
        assertThatThrownBy(()->service.reindex(who,9)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rememberingSceneNeverMakesAnImplicitPaidEmbeddingCall() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE vision_memory(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id VARCHAR,tenant_id VARCHAR,kind VARCHAR,content VARCHAR,structured_json VARCHAR,source VARCHAR,created_at TIMESTAMP,updated_at TIMESTAMP)");
        var service=new VisionMemoryService(db,(who,call,budget)->{throw new AssertionError("scene persistence must not incur an implicit paid call");},.5);
        service.rememberScene(new ChatIdentity("tenant","owner",true),new com.bbb.exercise.agentdemo.chatservice.generation.SceneCard("cat",List.of("cat"),"warm","center","calm","asset"),"remember");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vision_memory",Long.class)).isEqualTo(1);
    }
    @Test void synonymsAreRetrievedByVectorWhileTenantAndModelMismatchesAreExcluded() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE vision_memory(id BIGINT PRIMARY KEY,tenant_id VARCHAR,user_id VARCHAR,kind VARCHAR,content VARCHAR,active INT,updated_at TIMESTAMP,embedding_json VARCHAR,embedding_fingerprint VARCHAR,embedding_dimensions INT)");
        insert(db,1,"tenant","owner","a sleeping feline","[1,0]","v1");
        insert(db,2,"tenant","owner","cathedral architecture","[0,1]","v1");
        insert(db,3,"other","owner","private tenant cat","[1,0]","v1");
        insert(db,4,"tenant","victim","private user cat","[1,0]","v1");
        insert(db,5,"tenant","owner","obsolete space","[1,0]","v0");
        insert(db,6,"tenant","owner","legacy data",null,null);
        var model=mock(EmbeddingModel.class);when(model.embed("小猫")).thenReturn(new float[]{1,0});
        VisionEmbeddings embeddings=(who,call,budget)->new VisionEmbeddings.Space(model,"v1",2,()->9L);
        var service=new VisionMemoryService(db,embeddings,.5);
        var result=service.search(new ChatIdentity("tenant","owner",true),"小猫",8,"call",100L);
        assertThat(result.context()).contains("sleeping feline").doesNotContain("cathedral","private","obsolete","legacy");
        assertThat(result.inputTokens()).isEqualTo(9);assertThat(result.hits()).isEqualTo(1);
    }
    void insert(JdbcTemplate db,long id,String tenant,String owner,String content,String vector,String version) {db.update("INSERT INTO vision_memory VALUES(?,?,?,'SCENE',?,1,CURRENT_TIMESTAMP,?,?,2)",id,tenant,owner,content,vector,version);}
}
