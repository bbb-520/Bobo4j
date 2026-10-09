package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ExecutionReplayTest {
    ExecutionStore store(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);return new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());}
    @Test void replayPastStoppedEventStillReachesResumedAnswer() {
        var store=store();var i=new ChatIdentity("t","u",true);
        var row=store.create(i,new ExecutionStore.Create("r","CHAT","q",null,null,false));
        row=store.stop(i,row.view().executionId());
        store.resume(i,row.view().executionId(),new ExecutionStore.Resume("resume",row.view().version(),4,null));
        var lease=store.claim("worker").orElseThrow();store.complete(lease,"restored answer",List.of(),"NOT_APPLICABLE");
        var events=new ExecutionController(store,null,null).stream(i,row.view().executionId(),0).collectList().block(Duration.ofSeconds(5));
        assertThat(events).isNotNull();assertThat(events).extracting(e->e.event()).contains("answer_committed");
        assertThat(events.getLast().data()).isInstanceOf(ExecutionStore.Event.class);
        assertThat(((ExecutionStore.Event)events.getLast().data()).payload().get("status")).isEqualTo("COMPLETED");
    }
    @Test void closingSubscriptionDoesNotStopQueuedExecution() {
        var store=store();var i=new ChatIdentity("t","u",true);
        var row=store.create(i,new ExecutionStore.Create("r","CHAT","q",null,null,false));
        new ExecutionController(store,null,null).stream(i,row.view().executionId(),0).take(1).blockLast(Duration.ofSeconds(3));
        assertThat(store.get(i,row.view().executionId()).view().status()).isEqualTo("QUEUED");
    }
    @Test void lastEventIdCannotSelectAnotherRun() {
        assertThatThrownBy(()->ExecutionController.cursor("run-b","run-a:4",0L)).hasMessageContaining("不一致");
        assertThat(ExecutionController.cursor("run","run:4",0L)).isEqualTo(4);
    }
}
