package com.bbb.exercise.agentdemo.auth.payment;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PaymentMigrationTest {
    @Test void manualRecoveryMigrationPreservesUncertainOrdersAndRefundAttempts() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),new ClassPathResource("db/migration/V6__free_tokens.sql"),new ClassPathResource("db/migration/V8__payment_recovery.sql"),new ClassPathResource("db/migration/V9__alipay_stability.sql")).execute(ds);
        try(var connection=ds.getConnection()){new db.migration.V11__refund_attempts().migrate(connection);}
        var db=new JdbcTemplate(ds);
        db.update("INSERT INTO billing_wallet(user_id,balance_micros,free_tokens) VALUES('legacy',1230000,900000)");
        db.update("INSERT INTO payment_order(id,user_id,idempotency_key,channel,amount_cents,reconcile_state,expires_at) VALUES('uncertain','legacy','key','ALIPAY',123,'ERROR',TIMESTAMPADD(DAY,-3,CURRENT_TIMESTAMP))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V12__payment_manual_recovery.sql")).execute(ds);
        assertThat(db.queryForObject("SELECT balance_micros FROM billing_wallet",Long.class)).isEqualTo(1230000);
        assertThat(db.queryForObject("SELECT status FROM payment_order",String.class)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT reconcile_state FROM payment_order",String.class)).isEqualTo("ERROR");
        assertThat(db.queryForObject("SELECT reconcile_resumed_at FROM payment_order",java.sql.Timestamp.class)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_audit",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_ledger",Integer.class)).isZero();
    }

    @Test void additiveMigrationPreservesLegacyWechatOrdersAndWalletsWithoutInventingLedgerHistory() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),new ClassPathResource("db/migration/V6__free_tokens.sql"),new ClassPathResource("db/migration/V8__payment_recovery.sql")).execute(ds);
        var db=new JdbcTemplate(ds);
        db.update("INSERT INTO billing_wallet(user_id,balance_micros,reserved_micros,free_images,free_tokens) VALUES('legacy',1230000,0,0,900000)");
        db.update("INSERT INTO payment_order(id,user_id,idempotency_key,channel,amount_cents,status,provider_trade_id,expires_at) VALUES('legacy-order','legacy','legacy-key','WECHAT',123,'PAID','legacy-trade',CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V9__alipay_stability.sql")).execute(ds);
        assertThat(db.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id='legacy'",Long.class)).isEqualTo(1230000);
        assertThat(db.queryForObject("SELECT free_tokens FROM billing_wallet WHERE user_id='legacy'",Long.class)).isEqualTo(900000);
        assertThat(db.queryForObject("SELECT channel FROM payment_order WHERE id='legacy-order'",String.class)).isEqualTo("WECHAT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_ledger",Integer.class)).isZero();
    }
}
