package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real InnoDB regression. Run only against the isolated execution_verify database. */
@EnabledIfEnvironmentVariable(named="EXECUTION_TEST_MYSQL_URL",matches=".+")
class ExecutionMySqlConcurrencyTest {
    static DriverManagerDataSource ds;
    @BeforeEach void schema() {
        ds=new DriverManagerDataSource(System.getenv("EXECUTION_TEST_MYSQL_URL"),"verify","verify-only");
        assertThat(new JdbcTemplate(ds).queryForObject("SELECT DATABASE()",String.class)).isEqualTo("execution_verify");
        for(String table:List.of("agent_execution_outbox","agent_execution_checkpoint","agent_execution_resume","agent_execution_event","agent_execution_step","agent_execution"))new JdbcTemplate(ds).execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__persistent_executions.sql")).execute(ds);
    }
    @Test void oldLeaseCannotWriteAfterWaitingForACommittedTakeover() throws Exception {
        var atLock=new CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean(false);var owner=new ChatIdentity("tenant","user",true);
        var jdbc=new JdbcTemplate(ds) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... values) {
                if(armed.get()&&sql.contains("FOR UPDATE"))atLock.countDown();return super.queryForObject(sql,type,values);
            }
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... values) {
                if(armed.get()&&sql.contains("FOR UPDATE"))atLock.countDown();return super.query(sql,mapper,values);
            }
        };
        var store=new ExecutionStore(jdbc,new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var row=store.create(owner,new ExecutionStore.Create(UUID.randomUUID().toString(),"CHAT","question",null,null,false));
        var lease=store.claim("old-worker").orElseThrow();
        assertThat(lease.executionId()).isEqualTo(row.view().executionId());armed.set(true);
        try(var blocker=ds.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM agent_execution WHERE id=? FOR UPDATE")){lock.setString(1,row.view().executionId());lock.executeQuery().close();}
            var future=executor.submit(()->store.event(lease,"progress",Map.of("message","stale")));
            assertThat(atLock.await(5,TimeUnit.SECONDS)).isTrue();
            try(var takeover=blocker.prepareStatement("UPDATE agent_execution SET lease_token='new-lease',lease_until=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE id=?")){takeover.setString(1,row.view().executionId());takeover.executeUpdate();}
            blocker.commit();
            assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(store.events(owner,row.view().executionId(),0,200)).noneMatch(e->e.payload().containsValue("stale"));
        }
    }
    @Test void simultaneousIdenticalCreatesAllReturnTheOneExecution() throws Exception {
        var store=new ExecutionStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),JsonMapper.builder().build());
        var owner=new ChatIdentity("tenant","create-user",true);
        var input=new ExecutionStore.Create(UUID.randomUUID().toString(),"CHAT","question",null,null,false);
        var barrier=new CyclicBarrier(8);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> attempts=new ArrayList<>();
            for(int i=0;i<8;i++)attempts.add(executor.submit(()->{barrier.await();return store.create(owner,input).view().executionId();}));
            Set<String> ids=new HashSet<>();for(var attempt:attempts)ids.add(attempt.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
    }
}
