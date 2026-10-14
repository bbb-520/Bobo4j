package com.bbb.exercise.agentdemo.auth.billing;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PaymentRefundAttemptsTest {
    JdbcTemplate db; PaymentRefundService refunds; String order;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),new ClassPathResource("db/migration/V6__free_tokens.sql"),new ClassPathResource("db/migration/V8__payment_recovery.sql"),new ClassPathResource("db/migration/V9__alipay_stability.sql"),new ClassPathResource("db/migration/V12__payment_manual_recovery.sql")).execute(ds);
        try(var connection=ds.getConnection()){new db.migration.V11__refund_attempts().migrate(connection);}
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new BillingService(db,tx,10000,200000,1000000,500000).createWallet("alice",0);
        var payments=new PaymentService(db,tx);
        order=payments.create("alice",UUID.randomUUID().toString(),"ALIPAY",123).id();
        payments.credit(order,"ALIPAY","trade",123,"CNY");
        refunds=new PaymentRefundService(db,tx);
    }
    void reject(PaymentRefundService.Refund refund) {
        db.update("UPDATE payment_refund SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",refund.id());
        String owner=refunds.acquire(refund.id());refunds.attempt(refund.id(),owner);refunds.rejectFirstAttempt(refund.id(),owner);
        refunds.rejectFirstAttempt(refund.id(),owner); // A duplicated provider receipt cannot release twice.
    }
    @Test void confirmedFailureAllowsNewAuditableAttemptWithoutOverwritingHistory() {
        var first=refunds.begin(order,"admin","first attempt");reject(first);
        assertThat(balance()).isEqualTo(1230000);
        assertThat(refunds.begin(order,"admin","first attempt").id()).isEqualTo(first.id());
        var second=refunds.begin(order,"admin","operator retry after rejection");
        assertThat(second.id()).isNotEqualTo(first.id());assertThat(second.status()).isEqualTo("PENDING");
        assertThat(refunds.get(first.id()).status()).isEqualTo("FAILED");assertThat(balance()).isZero();
        assertThat(refunds.begin(order,"admin","operator retry after rejection").id()).isEqualTo(second.id());
        assertThat(refunds.forOrder(order).id()).isEqualTo(second.id());
    }
    @Test void unknownAttemptNeverCreatesAnotherHoldEvenWithNewReason() {
        var first=refunds.begin(order,"admin","unknown receipt");
        assertThat(refunds.begin(order,"admin","new request while pending").id()).isEqualTo(first.id());
        assertThat(balance()).isZero();
    }
    long balance(){return db.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='alice'",Long.class);}
}
