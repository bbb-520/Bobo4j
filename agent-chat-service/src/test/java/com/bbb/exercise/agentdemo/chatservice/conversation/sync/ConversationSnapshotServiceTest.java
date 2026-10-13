package com.bbb.exercise.agentdemo.chatservice.conversation.sync;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ConversationSnapshotServiceTest {
    final ChatIdentity alice = new ChatIdentity("tenant", "alice", true);
    final ChatIdentity bob = new ChatIdentity("tenant", "bob", true);
    final String id = UUID.randomUUID().toString();
    ConversationSnapshotService service;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE conversation_snapshot(tenant_id VARCHAR(128),user_id VARCHAR(255),conversation_id VARCHAR(36),revision BIGINT,deleted BOOLEAN,title VARCHAR(255),snapshot_json CLOB,updated_at BIGINT,PRIMARY KEY(tenant_id,user_id,conversation_id))");
        service = new ConversationSnapshotService(jdbc);
    }

    Map<String,Object> snapshot() {
        return Map.of("id",id,"title","test","createdAt",1,"updatedAt",2,"messages",List.of(Map.of("id","u1","role","user","content","hello","attachments",List.of(Map.of("assetId","asset","previewUrl","https://secret.example/signed")))));
    }

    @Test void restoresDurableIdsWithoutPersistingSignedPreviewUrls() {
        var saved = service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(service.get(alice,id).snapshot().toString()).contains("u1","asset").doesNotContain("secret.example","previewUrl");
        assertThat(service.list(alice,0,100)).hasSize(1);
    }

    @Test void isolatesBothTenantAndUserAndRequiresAuthentication() {
        service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()));
        assertThat(service.list(bob,0,100)).isEmpty();
        assertThat(service.list(new ChatIdentity("other", "alice", true),0,100)).isEmpty();
        assertThat(service.list(new ChatIdentity("Tenant", "alice", true),0,100)).isEmpty();
        assertThat(service.list(new ChatIdentity("tenant", "Alice", true),0,100)).isEmpty();
        assertThatThrownBy(()->service.get(bob,id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(()->service.list(new ChatIdentity("tenant","alice",false),0,100)).hasMessageContaining("401");
    }

    @Test void rejectsStaleWriterAndDeletionCannotBeResurrected() {
        service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()));
        assertThatThrownBy(()->service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()))).hasMessageContaining("409");
        service.put(alice,id,new ConversationSnapshotService.Write(1,true,null));
        assertThat(service.list(alice,0,100).getFirst().deleted()).isTrue();
        assertThat(service.get(alice,id).snapshot()).isNull();
        assertThatThrownBy(()->service.put(alice,id,new ConversationSnapshotService.Write(2,false,snapshot()))).hasMessageContaining("410");
    }

    @Test void validatesIdSizeAndMandatoryConversationStructure() {
        assertThatThrownBy(()->service.put(alice,"../evil",new ConversationSnapshotService.Write(0,false,snapshot()))).hasMessageContaining("400");
        assertThatThrownBy(()->service.put(alice,id,new ConversationSnapshotService.Write(0,false,Map.of("id",id)))).hasMessageContaining("400");
        var large = new java.util.HashMap<>(snapshot());
        large.put("content","x".repeat(1024*1024+1));
        assertThatThrownBy(()->service.put(alice,id,new ConversationSnapshotService.Write(0,false,large))).hasMessageContaining("413");
        assertThat(service.list(alice,0,100)).isEmpty();
    }

    @Test void onlyOneConcurrentWriterCanAdvanceTheSameRevision() throws Exception {
        service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> write = () -> {
                barrier.await();
                try {service.put(alice,id,new ConversationSnapshotService.Write(1,false,snapshot()));return 200;}
                catch(ResponseStatusException conflict) {return conflict.getStatusCode().value();}
            };
            var first=workers.submit(write);var second=workers.submit(write);
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(service.get(alice,id).revision()).isEqualTo(2);
    }

    @Test void aFutureRevisionCannotResurrectADeletionBetweenReadAndUpdate() {
        service.put(alice,id,new ConversationSnapshotService.Write(0,false,snapshot()));
        var pendingDelete = new java.util.concurrent.atomic.AtomicBoolean(true);
        var delayedRead = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                var rows = super.query(sql,mapper,args);
                if(sql.startsWith("SELECT revision") && pendingDelete.compareAndSet(true,false))
                    service.put(alice,id,new ConversationSnapshotService.Write(1,true,null));
                return rows;
            }
        };
        var concurrentWriter = new ConversationSnapshotService(delayedRead);
        assertThatThrownBy(()->concurrentWriter.put(alice,id,new ConversationSnapshotService.Write(2,false,snapshot()))).hasMessageContaining("409");
        assertThat(service.get(alice,id).deleted()).isTrue();
    }
}
