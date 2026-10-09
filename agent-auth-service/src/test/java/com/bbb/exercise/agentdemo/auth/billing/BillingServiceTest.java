package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BillingServiceTest {
    JdbcTemplate jdbc; BillingService billing; TransactionTemplate tx;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"), new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        billing = new BillingService(jdbc, tx, 10000, 200000, 1000000, 500000);
        billing.createWallet("alice", 2);
    }
    @Test void onlyTwoImageTrialsCanBeConsumed() {
        for (int i=0;i<2;i++) { String id="image-"+i; billing.reserve("alice",new Reserve(id,"IMAGE","qwen-image")); billing.dispatch("alice",id); billing.settle("alice",id,new Settlement(100L,200L,"req-"+i)); }
        assertThat(billing.wallet("alice").freeImages()).isZero();
        assertThatThrownBy(() -> billing.reserve("alice",new Reserve("third","IMAGE","qwen-image"))).isInstanceOf(BillingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage WHERE status='SUCCEEDED'",Long.class)).isEqualTo(2);
    }
    @Test void embeddingRerankAndEvaluationUseMeasuredTokenBilling() {
        jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id='alice'");
        for (String capability : java.util.List.of("EMBEDDING", "RERANK", "EVALUATION")) {
            String id = "new-" + capability;
            billing.reserve("alice", new Reserve(id, capability, "model"));
            billing.dispatch("alice", id);
            billing.settle("alice", id, new Settlement(100L, 0L, "receipt-" + capability));
        }
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(1997000);
    }
    @Test void theLastTrialExhaustsCreditWithoutCreatingDebt() {
        jdbc.update("UPDATE billing_wallet SET free_images=1 WHERE user_id='alice'");
        billing.reserve("alice",new Reserve("last-trial","IMAGE","qwen-image"));
        billing.dispatch("alice","last-trial");
        assertThat(billing.wallet("alice").arrears()).isFalse();
        assertThat(billing.wallet("alice").blocked()).isTrue();
        billing.settle("alice","last-trial",new Settlement(null,null,"receipt"));
        assertThat(billing.wallet("alice").arrears()).isFalse();
        assertThat(billing.wallet("alice").lowBalance()).isTrue();
    }
    @Test void settlementIsIdempotentAndRatesAreSnapshotted() {
        jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id='alice'");
        billing.reserve("alice",new Reserve("text-1","CHAT","qwen-turbo")); billing.dispatch("alice","text-1");
        billing.settle("alice","text-1",new Settlement(120L,80L,"req"));
        billing.settle("alice","text-1",new Settlement(120L,80L,"req"));
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(1998000);
        assertThat(billing.wallet("alice").reservedMicros()).isZero();
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM model_usage WHERE id='text-1'",Long.class)).isEqualTo(2000);
        assertThatThrownBy(() -> billing.settle("alice","text-1",new Settlement(121L,80L,"req"))).isInstanceOf(BillingException.class);
    }
    @Test void unknownOutcomeRetainsFundsAndStopsFurtherCalls() {
        jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id='alice'");
        billing.reserve("alice",new Reserve("timeout","CHAT","qwen-turbo")); billing.dispatch("alice","timeout"); billing.unknown("alice","timeout");
        assertThat(billing.wallet("alice").reservedMicros()).isEqualTo(200000);
        assertThatThrownBy(() -> billing.reserve("alice",new Reserve("next","CHAT","qwen-turbo"))).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> billing.cancel("alice","timeout")).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> billing.dispatch("alice","timeout")).isInstanceOf(BillingException.class);
    }
    @Test void concurrentReservationsAllowOnlyOneInFlightAndDoNotDoubleSpend() throws Exception {
        var start = new CountDownLatch(1); var pool=Executors.newFixedThreadPool(8); var successes=new AtomicInteger();
        try {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<8;i++) { String id="parallel-"+i; tasks.add(pool.submit(() -> { try { start.await(); billing.reserve("alice",new Reserve(id,"IMAGE","qwen-image")); successes.incrementAndGet(); } catch (BillingException expected) {} catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } })); }
            start.countDown(); for(var task:tasks) task.get(20,TimeUnit.SECONDS);
            assertThat(successes.get()).isEqualTo(1); assertThat(billing.wallet("alice").freeImages()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void imageWithoutTokenUsageUsesExplicitImageRate() {
        jdbc.update("UPDATE billing_wallet SET free_images=0,balance_micros=2000000 WHERE user_id='alice'");
        billing.reserve("alice",new Reserve("image-paid","IMAGE","qwen-image")); billing.dispatch("alice","image-paid");
        billing.settle("alice","image-paid",new Settlement(null,null,"request"));
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(1000000);
        assertThat(jdbc.queryForObject("SELECT metering FROM model_usage WHERE id='image-paid'",String.class)).isEqualTo("IMAGE");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_usage WHERE id='image-paid'",Long.class)).isNull();
    }
    @Test void zeroOrNegativeBalanceStopsPaidGenerationAndReportsWarning() {
        jdbc.update("UPDATE billing_wallet SET free_images=0,balance_micros=-1 WHERE user_id='alice'");
        assertThat(billing.wallet("alice").arrears()).isTrue();
        assertThat(billing.wallet("alice").lowBalance()).isTrue();
        assertThatThrownBy(() -> billing.reserve("alice",new Reserve("debt","CHAT","qwen-turbo"))).isInstanceOf(BillingException.class);
    }
    @Test void cancelRestoresOnlyUndispatchedTrialAndChangedRequestKeyIsRejected() {
        billing.reserve("alice",new Reserve("unused","IMAGE","qwen-image"));
        assertThatThrownBy(() -> billing.reserve("alice",new Reserve("unused","CHAT","qwen-turbo"))).isInstanceOf(BillingException.class);
        billing.cancel("alice","unused"); billing.cancel("alice","unused");
        assertThat(billing.wallet("alice").freeImages()).isEqualTo(2);
    }
    @Test void preDispatchFailureAndExpiredReservationReleaseFundsButDispatchedDoesNot() {
        billing.reserve("alice",new Reserve("preflight","IMAGE","qwen-image"));billing.unknown("alice","preflight");
        assertThat(billing.wallet("alice").freeImages()).isEqualTo(2);assertThat(billing.wallet("alice").activeUsageId()).isNull();
        billing.reserve("alice",new Reserve("expired","IMAGE","qwen-image"));
        jdbc.update("UPDATE model_usage SET created_at=? WHERE id='expired'",java.time.LocalDateTime.now().minusMinutes(6));
        billing.expireReservations();assertThat(billing.wallet("alice").freeImages()).isEqualTo(2);
        billing.reserve("alice",new Reserve("sent","IMAGE","qwen-image"));billing.dispatch("alice","sent");
        jdbc.update("UPDATE model_usage SET created_at=? WHERE id='sent'",java.time.LocalDateTime.now().minusMinutes(6));
        billing.expireReservations();assertThat(billing.wallet("alice").activeUsageId()).isEqualTo("sent");
    }
    @Test void debtRemainsBlockedEvenWhenImageTrialsRemain() {
        jdbc.update("UPDATE billing_wallet SET balance_micros=-1,free_images=2 WHERE user_id='alice'");
        assertThat(billing.wallet("alice").arrears()).isTrue();assertThat(billing.wallet("alice").blocked()).isTrue();
    }
    @Test void operatorRefundIsAuditedAndCannotCreditTwice() {
        jdbc.update("UPDATE billing_wallet SET balance_micros=2000000,free_images=0 WHERE user_id='alice'");
        billing.reserve("alice",new Reserve("refund","CHAT","qwen-turbo"));billing.dispatch("alice","refund");
        billing.settle("alice","refund",new Settlement(120L,80L,"r"));
        billing.reconcileRefund("alice","refund","operator","供应商确认退款单ABC");billing.reconcileRefund("alice","refund","operator","供应商确认退款单ABC");
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(2000000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_reconciliation",Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> billing.settle("alice","refund",new Settlement(120L,80L,"r"))).isInstanceOf(BillingException.class);
    }
}
