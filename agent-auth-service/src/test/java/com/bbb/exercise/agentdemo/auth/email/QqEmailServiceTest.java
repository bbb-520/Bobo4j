package com.bbb.exercise.agentdemo.auth.email;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import com.bbb.exercise.agentdemo.auth.AuthService.AuthException;

class QqEmailServiceTest {
    JdbcTemplate jdbc; QqEmailService codes; String sent;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE email_code(email VARCHAR(254),purpose VARCHAR(16),code_hash CHAR(64),expires_at TIMESTAMP,attempts INT DEFAULT 0,consumed BOOLEAN DEFAULT FALSE,PRIMARY KEY(email,purpose))");
        jdbc.execute("CREATE TABLE email_send_limit(limit_key VARCHAR(128) PRIMARY KEY,count_value INT DEFAULT 0,reset_at TIMESTAMP)");
        codes=new QqEmailService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),(email,code)->sent=code,"0123456789abcdef0123456789abcdef");
    }
    @Test void consumesOnceAndBindsPurpose() {
        codes.send("123456@QQ.com","LOGIN","127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_code",String.class)).doesNotContain(sent);
        assertThatThrownBy(() -> codes.consume("123456@qq.com","REGISTER",sent)).isInstanceOf(AuthException.class);
        assertThat(codes.consume("123456@qq.com","LOGIN",sent)).isEqualTo("123456@qq.com");
        assertThatThrownBy(() -> codes.consume("123456@qq.com","LOGIN",sent)).isInstanceOf(AuthException.class);
    }
    @Test void failedAttemptsRemainCommittedAndBlockCorrectCode() {
        codes.send("123456@qq.com","LOGIN","127.0.0.1");String wrong="000000".equals(sent)?"000001":"000000";
        for(int i=0;i<5;i++) assertThatThrownBy(() -> codes.consume("123456@qq.com","LOGIN",wrong)).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_code",Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> codes.consume("123456@qq.com","LOGIN",sent)).isInstanceOf(AuthException.class);
    }
    @Test void rejectsOtherDomainsCooldownAndExpiredCode() {
        assertThatThrownBy(() -> codes.send("test@gmail.com","LOGIN","ip")).isInstanceOf(AuthException.class);
        codes.send("123456@qq.com","LOGIN","ip");
        assertThatThrownBy(() -> codes.send("123456@qq.com","REGISTER","ip")).isInstanceOf(AuthException.class);
        jdbc.update("UPDATE email_code SET expires_at=?",java.time.LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> codes.consume("123456@qq.com","LOGIN",sent)).isInstanceOf(AuthException.class);
    }
    @Test void concurrentLoginConsumesOneCodeOnly() throws Exception {
        codes.send("123456@qq.com","LOGIN","ip");var pool=Executors.newFixedThreadPool(8);var success=new AtomicInteger();
        try {var tasks=new java.util.ArrayList<Future<?>>();for(int i=0;i<8;i++) tasks.add(pool.submit(() -> {try{codes.consume("123456@qq.com","LOGIN",sent);success.incrementAndGet();}catch(AuthException expected){}}));
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);assertThat(success.get()).isEqualTo(1);
        }finally {pool.shutdownNow();}
    }
    @Test void expiryUsesDatabaseClockEvenWhenApplicationWallClockDiffers() {
        var ahead=java.time.LocalDateTime.now().plusHours(2);
        try(var clock=org.mockito.Mockito.mockStatic(java.time.LocalDateTime.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            codes.send("123456@qq.com","LOGIN","ip");
            long seconds=jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM email_code",Long.class);
            assertThat(seconds).isBetween(295L,300L);
        }
    }
    @Test void clockSkewDoesNotBypassCooldown() {
        codes.send("123456@qq.com","LOGIN","ip");var ahead=java.time.LocalDateTime.now().plusHours(2);
        try(var clock=org.mockito.Mockito.mockStatic(java.time.LocalDateTime.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            assertThatThrownBy(() -> codes.send("123456@qq.com","LOGIN","ip")).isInstanceOf(AuthException.class);
        }
    }
    @Test void clockSkewDoesNotInvalidateAnUnexpiredCode() {
        codes.send("123456@qq.com","LOGIN","ip");var ahead=java.time.LocalDateTime.now().plusHours(2);
        try(var clock=org.mockito.Mockito.mockStatic(java.time.LocalDateTime.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            assertThat(codes.consume("123456@qq.com","LOGIN",sent)).isEqualTo("123456@qq.com");
        }
    }
}
