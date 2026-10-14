package com.bbb.exercise.agentdemo.auth.billing;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 验证支付通知仅入账一次，并检查订单归属、金额、渠道及数据库时钟约束。 */
class PaymentServiceTest {
    JdbcTemplate jdbc;
    PaymentService payments;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa",
                        "");

        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V3__billing.sql"),
                        new ClassPathResource("db/migration/V6__free_tokens.sql"),
                        new ClassPathResource("db/migration/V8__payment_recovery.sql"),new ClassPathResource("db/migration/V9__alipay_stability.sql"),new ClassPathResource("db/migration/V12__payment_manual_recovery.sql"))
                .execute(ds);
        new BillingService(jdbc, tx, 10000, 200000, 1000000, 500000).createWallet("alice", 0);
        payments = new PaymentService(jdbc, tx);
    }

    @Test
    void expiredUncertainOrdersStopAutomaticQueriesWithoutChangingFinancialTruth() {
        var order=payments.create("alice","uncertain","ALIPAY",100);
        jdbc.update("UPDATE payment_order SET expires_at=TIMESTAMPADD(HOUR,-25,CURRENT_TIMESTAMP),reconcile_state='ERROR' WHERE id=?",order.id());
        String owner=payments.acquireSync(order.id());
        payments.finishSync(order.id(),owner,"provider unavailable");
        assertThat(payments.find(order.id()).reconcileState()).isEqualTo("MANUAL");
        assertThat(payments.find(order.id()).status()).isEqualTo("PENDING");
        jdbc.update("UPDATE payment_order SET next_check_at=CURRENT_TIMESTAMP WHERE id=?",order.id());
        assertThat(payments.dueOrders()).isEmpty();
        assertThat(payments.acquireSync(order.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class)).isZero();
        payments.credit(order.id(),"ALIPAY","verified-late",100,"CNY");
        payments.credit(order.id(),"ALIPAY","verified-late",100,"CNY");
        assertThat(payments.find(order.id()).status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class)).isEqualTo(1000000);
    }

    @Test
    void manualResumeIsConcurrentIdempotentAuditedAndGrantsANewBoundedQueryWindow() throws Exception {
        var order=payments.create("alice","resume","ALIPAY",100);
        jdbc.update("UPDATE payment_order SET expires_at=TIMESTAMPADD(HOUR,-25,CURRENT_TIMESTAMP) WHERE id=?",order.id());
        var lease=payments.acquireSync(order.id());payments.finishSync(order.id(),lease,"timeout");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->payments.resumeReconciliation(order.id(),"admin","渠道工单确认可继续查询")));
            for(var task:tasks)task.get(20,TimeUnit.SECONDS);
        }
        assertThat(payments.find(order.id()).reconcileState()).isEqualTo("UNVERIFIED");
        assertThat(payments.dueOrders()).extracting(PaymentService.Order::id).containsExactly(order.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_audit WHERE action='RESUME'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_audit WHERE action='MANUAL' AND reason='EXPIRED_PAYMENT_OUTCOME_UNKNOWN'",Integer.class)).isEqualTo(1);
        lease=payments.acquireSync(order.id());payments.finishSync(order.id(),lease,"still uncertain");
        assertThat(payments.find(order.id()).reconcileState()).isEqualTo("ERROR");
        jdbc.update("UPDATE payment_order SET reconcile_resumed_at=TIMESTAMPADD(HOUR,-25,CURRENT_TIMESTAMP),next_check_at=CURRENT_TIMESTAMP WHERE id=?",order.id());
        lease=payments.acquireSync(order.id());payments.finishSync(order.id(),lease,"still uncertain");
        assertThat(payments.find(order.id()).reconcileState()).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_audit WHERE action='MANUAL'",Integer.class)).isEqualTo(2);
    }

    @Test
    void uncertaintyUsesExpiryNotCreationAndRejectsStaleLease() {
        org.springframework.test.util.ReflectionTestUtils.setField(payments,"expiredUncertaintySeconds",60);
        var order=payments.create("alice","near-expiry","ALIPAY",100);
        jdbc.update("UPDATE payment_order SET created_at=TIMESTAMPADD(DAY,-3,CURRENT_TIMESTAMP),expires_at=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP) WHERE id=?",order.id());
        var lease=payments.acquireSync(order.id());
        assertThat(payments.escalateExpiredUncertainty(order.id(),lease)).isFalse();
        jdbc.update("UPDATE payment_order SET expires_at=TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP) WHERE id=?",order.id());
        assertThat(payments.escalateExpiredUncertainty(order.id(),"stale-owner")).isFalse();
        assertThat(payments.escalateExpiredUncertainty(order.id(),lease)).isTrue();
        payments.finishSync(order.id(),lease,"timeout");
        assertThat(payments.find(order.id()).reconcileState()).isEqualTo("MANUAL");
    }

    @Test
    void repeatedAndConcurrentNotificationsCreditExactlyOnce() throws Exception {
        var order = payments.create("alice", "order-key", "ALIPAY", 1000);
        var pool = Executors.newFixedThreadPool(8);

        try {
            var futures = new java.util.ArrayList<Future<?>>();

            for (int i = 0; i < 8; i++) {
                futures.add(
                        pool.submit(
                                () ->
                                        payments.credit(
                                                order.id(), "ALIPAY", "trade-001", 1000, "CNY")));
            }

            for (var f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",
                                Long.class))
                .isEqualTo(10000000);
        assertThat(payments.get("alice", order.id()).status()).isEqualTo("PAID");
    }

    @Test
    void wrongAmountChannelOrCurrencyDoesNotCredit() {
        var order = payments.create("alice", "order-key", "ALIPAY", 1000);

        assertThatThrownBy(() -> payments.credit(order.id(), "ALIPAY", "trade", 999, "CNY"))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.credit(order.id(), "WECHAT", "trade", 1000, "CNY"))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.credit(order.id(), "ALIPAY", "trade", 1000, "USD"))
                .isInstanceOf(BillingException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",
                                Long.class))
                .isZero();
    }

    @Test
    void idempotencyKeyCannotChangeOrderAndOwnershipIsEnforced() {
        var order = payments.create("alice", "key", "ALIPAY", 100);

        assertThat(payments.create("alice", "key", "ALIPAY", 100).id()).isEqualTo(order.id());
        assertThatThrownBy(() -> payments.create("alice", "key", "ALIPAY", 200))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.get("bob", order.id()))
                .isInstanceOf(BillingException.class);
    }

    @Test
    void providerTransactionCannotCreditTwoOrders() {
        var a = payments.create("alice", "a", "ALIPAY", 100);
        var b = payments.create("alice", "b", "ALIPAY", 100);

        payments.credit(a.id(), "ALIPAY", "one-trade", 100, "CNY");
        assertThatThrownBy(() -> payments.credit(b.id(), "ALIPAY", "one-trade", 100, "CNY"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(payments.get("alice", b.id()).status()).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",
                                Long.class))
                .isEqualTo(1000000);
    }

    @Test
    void orderExpiryUsesDatabaseClockEvenWhenApplicationWallClockDiffers() {
        var ahead = java.time.LocalDateTime.now().plusHours(2);

        try (var applicationClock =
                org.mockito.Mockito.mockStatic(
                        java.time.LocalDateTime.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            applicationClock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            var order = payments.create("alice", "clock-order", "ALIPAY", 100);
            long seconds =
                    jdbc.queryForObject(
                            "SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM payment_order WHERE id=?",
                            Long.class,
                            order.id());

            assertThat(seconds).isBetween(1795L, 1800L);
        }
    }

    @Test
    void prepayLeaseUsesDatabaseClockEvenWhenApplicationWallClockDiffers() {
        var order = payments.create("alice", "clock-lease", "ALIPAY", 100);
        var ahead = java.time.LocalDateTime.now().plusHours(2);

        try (var applicationClock =
                org.mockito.Mockito.mockStatic(
                        java.time.LocalDateTime.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            applicationClock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            var lease = payments.acquirePrepay("alice", order.id());

            assertThat(lease).isNotBlank();
            long seconds =
                    jdbc.queryForObject(
                            "SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,prepay_until) FROM payment_order WHERE id=?",
                            Long.class,
                            order.id());

            assertThat(seconds).isBetween(55L, 60L);
        }
    }
}
