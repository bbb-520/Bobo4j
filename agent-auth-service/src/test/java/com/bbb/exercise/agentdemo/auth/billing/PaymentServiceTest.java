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

class PaymentServiceTest {
    JdbcTemplate jdbc; PaymentService payments;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds); var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"), new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        new BillingService(jdbc,tx,10000,200000,1000000,500000).createWallet("alice",0);
        payments=new PaymentService(jdbc,tx);
    }
    @Test void repeatedAndConcurrentNotificationsCreditExactlyOnce() throws Exception {
        var order=payments.create("alice","order-key","ALIPAY",1000);
        var pool=Executors.newFixedThreadPool(8);
        try { var futures=new java.util.ArrayList<Future<?>>(); for(int i=0;i<8;i++) futures.add(pool.submit(() -> payments.credit(order.id(),"ALIPAY","trade-001",1000,"CNY")));
            for(var f:futures) f.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class)).isEqualTo(10000000);
        assertThat(payments.get("alice",order.id()).status()).isEqualTo("PAID");
    }
    @Test void wrongAmountChannelOrCurrencyDoesNotCredit() {
        var order=payments.create("alice","order-key","WECHAT",1000);
        assertThatThrownBy(() -> payments.credit(order.id(),"WECHAT","trade",999,"CNY")).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.credit(order.id(),"ALIPAY","trade",1000,"CNY")).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.credit(order.id(),"WECHAT","trade",1000,"USD")).isInstanceOf(BillingException.class);
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class)).isZero();
    }
    @Test void idempotencyKeyCannotChangeOrderAndOwnershipIsEnforced() {
        var order=payments.create("alice","key","ALIPAY",100);
        assertThat(payments.create("alice","key","ALIPAY",100).id()).isEqualTo(order.id());
        assertThatThrownBy(() -> payments.create("alice","key","ALIPAY",200)).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> payments.get("bob",order.id())).isInstanceOf(BillingException.class);
    }
    @Test void providerTransactionCannotCreditTwoOrders() {
        var a=payments.create("alice","a","ALIPAY",100); var b=payments.create("alice","b","ALIPAY",100);
        payments.credit(a.id(),"ALIPAY","one-trade",100,"CNY");
        assertThatThrownBy(() -> payments.credit(b.id(),"ALIPAY","one-trade",100,"CNY")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(payments.get("alice",b.id()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class)).isEqualTo(1000000);
    }
    @Test void orderExpiryUsesDatabaseClockEvenWhenApplicationWallClockDiffers() {
        var ahead = java.time.LocalDateTime.now().plusHours(2);
        try (var applicationClock = org.mockito.Mockito.mockStatic(java.time.LocalDateTime.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            applicationClock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            var order = payments.create("alice", "clock-order", "WECHAT", 100);
            long seconds = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM payment_order WHERE id=?",
                    Long.class, order.id());
            assertThat(seconds).isBetween(1795L, 1800L);
        }
    }
    @Test void prepayLeaseUsesDatabaseClockEvenWhenApplicationWallClockDiffers() {
        var order = payments.create("alice", "clock-lease", "WECHAT", 100);
        var ahead = java.time.LocalDateTime.now().plusHours(2);
        try (var applicationClock = org.mockito.Mockito.mockStatic(java.time.LocalDateTime.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            applicationClock.when(java.time.LocalDateTime::now).thenReturn(ahead);
            var lease = payments.acquirePrepay("alice", order.id());
            assertThat(lease).isNotBlank();
            long seconds = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,prepay_until) FROM payment_order WHERE id=?",
                    Long.class, order.id());
            assertThat(seconds).isBetween(55L, 60L);
        }
    }
}
