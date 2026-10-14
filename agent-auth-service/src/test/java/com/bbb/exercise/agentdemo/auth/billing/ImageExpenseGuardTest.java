package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ImageExpenseGuardTest {
    JdbcTemplate db;
    BillingService billing;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        db = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        billing = new BillingService(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),10000,200000,1000000,500000);
        billing.createWallet("alice", 5, 5000000);
        billing.createWallet("bob", 5);
    }
    void unknown(String id) {
        billing.reserve("alice",new Reserve(id,"IMAGE","image"));
        billing.dispatch("alice",id);
        billing.unknown("alice",id);
    }
    @Test void thirdUnknownBlocksOnlyNewImagesAndKeepsNullUsageAndWalletRecovery() {
        unknown("a"); unknown("b"); unknown("c");
        assertThatThrownBy(()->billing.reserve("alice",new Reserve("blocked","IMAGE","image")))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE id='blocked'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE status='ABANDONED' AND input_tokens IS NULL AND output_tokens IS NULL",Integer.class)).isEqualTo(3);
        for(String cap:java.util.List.of("CHAT","EMBEDDING","RERANK","EVALUATION")) {
            billing.reserve("alice",new Reserve(cap,cap,"text"));billing.cancel("alice",cap);
        }
        billing.reserve("bob",new Reserve("bob-image","IMAGE","image"));
        billing.reserve("alice",new Reserve("new-chat","CHAT","text"));
        billing.unknown("alice","a"); billing.unknown("alice","b");
        assertThat(billing.wallet("alice").activeUsageId()).isEqualTo("new-chat");
        assertThat(billing.wallet("alice").freeImages()).isEqualTo(5);
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(5000000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM usage_reconciliation",Integer.class)).isEqualTo(3);
    }
    @Test void priceExposureCanTripBeforeFailureCountAndWindowExpires() {
        unknown("expensive");
        db.update("UPDATE model_usage SET image_price_micros=3000000 WHERE id='expensive'");
        assertThatThrownBy(()->billing.reserve("alice",new Reserve("blocked","IMAGE","image")))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        db.update("UPDATE usage_reconciliation SET created_at=TIMESTAMPADD(MINUTE,-16,CURRENT_TIMESTAMP)");
        billing.reserve("alice",new Reserve("after-window","IMAGE","image"));
    }
    @Test void undispatchedCancellationAndUnrelatedAbandonmentDoNotCount() {
        for(int i=0;i<4;i++) {
            billing.reserve("alice",new Reserve("cancel-"+i,"IMAGE","image"));billing.unknown("alice","cancel-"+i);
        }
        billing.reserve("alice",new Reserve("allowed","IMAGE","image"));
    }
    @Test void configuredFailureThresholdAndWindowAreApplied() {
        org.springframework.test.util.ReflectionTestUtils.setField(billing,"imageUnknownLimit",1);
        org.springframework.test.util.ReflectionTestUtils.setField(billing,"imageUnknownWindowSeconds",60);
        org.springframework.test.util.ReflectionTestUtils.setField(billing,"imageUnknownExposurePrices",100);
        unknown("configured");
        assertThatThrownBy(()->billing.reserve("alice",new Reserve("blocked","IMAGE","image")))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        db.update("UPDATE usage_reconciliation SET created_at=TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP)");
        billing.reserve("alice",new Reserve("allowed","IMAGE","image"));
    }
    @Test void guardStillReturnsExistingReservedRequestBeforeCheckingFailures() {
        unknown("a");unknown("b");
        billing.reserve("alice",new Reserve("reserved","IMAGE","image"));
        db.update("UPDATE model_usage SET image_price_micros=3000000 WHERE id='a'");
        assertThat(billing.reserve("alice",new Reserve("reserved","IMAGE","image")).requestId()).isEqualTo("reserved");
    }
    @Test void staleExecutionUncertaintyAlsoCounts() {
        for(int i=0;i<3;i++) {
            String id="stale-"+i;billing.reserve("alice",new Reserve(id,"IMAGE","image"));billing.dispatch("alice",id);
            db.update("UPDATE model_usage SET updated_at=TIMESTAMPADD(MINUTE,-20,CURRENT_TIMESTAMP) WHERE id=?",id);billing.expireReservations();
        }
        assertThatThrownBy(()->billing.reserve("alice",new Reserve("blocked","IMAGE","image")))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
    }
    @Test void concurrentThirdFailureAndAdmissionsCannotExceedThreshold() throws Exception {
        unknown("a"); unknown("b");
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);var admitted=new java.util.concurrent.atomic.AtomicInteger();
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<6;i++) {String id="race-"+i;tasks.add(pool.submit(()->{
                try {start.await();billing.reserve("alice",new Reserve(id,"IMAGE","image"));admitted.incrementAndGet();billing.dispatch("alice",id);billing.unknown("alice",id);}
                catch(BillingException expected) {assertThat(expected.getStatusCode().value()).isIn(409,429);}
                catch(InterruptedException e) {throw new RuntimeException(e);}
            }));}
            start.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);
            assertThat(admitted.get()).isEqualTo(1);
        }
    }
}
