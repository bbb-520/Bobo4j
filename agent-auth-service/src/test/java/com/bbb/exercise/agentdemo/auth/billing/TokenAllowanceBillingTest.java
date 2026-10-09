package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.auth.*;
import com.bbb.exercise.agentdemo.auth.email.QqEmailService;
import com.bbb.exercise.agentdemo.auth.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenAllowanceBillingTest {
    JdbcTemplate jdbc; BillingService billing; ModelCallGroupService groups; TransactionTemplate tx;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),
                new ClassPathResource("db/migration/V5__model_call_groups.sql"),
                new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        billing = new BillingService(jdbc, tx, 10000, 200000, 1000000, 500000);
        groups = new ModelCallGroupService(jdbc, tx, 10000, 200000, true, 400000, 1000000, 3, 30);
        billing.createWallet("alice", 0);
        jdbc.update("UPDATE billing_wallet SET free_tokens=1000000 WHERE user_id='alice'");
    }
    long free() { return jdbc.queryForObject("SELECT free_tokens FROM billing_wallet WHERE user_id='alice'", Long.class); }
    void settle(String id, long input, long output) {
        billing.reserve("alice", new Reserve(id, "CHAT", "qwen-turbo"));
        billing.dispatch("alice", id); billing.settle("alice", id, new Settlement(input, output, id));
    }
    @Test void registrationGrantsOneMillionAndDuplicateRegistrationCannotRefillIt() {
        var auth = mock(AuthService.class); var codes = mock(QqEmailService.class);
        var user = new AuthService.User(9, "new-user", "hash");
        when(codes.consume("123@qq.com", "REGISTER", "123456")).thenReturn("123@qq.com");
        when(auth.register("new-user", "password123")).thenReturn(user);
        when(auth.publicUserId(user)).thenReturn("new-user");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.bbb.exercise.agentdemo.auth.entity.AppUserEntity.class);
        var accounts = new AccountService(auth, mock(AppUserMapper.class), codes, billing, tx);
        accounts.register("new-user", "password123", "123@qq.com", "123456");
        assertThat(jdbc.queryForObject("SELECT free_tokens FROM billing_wallet WHERE user_id='new-user'", Long.class)).isEqualTo(1000000);
        assertThat(billing.wallet("new-user").freeImages()).isZero();
        jdbc.update("UPDATE billing_wallet SET free_tokens=900000 WHERE user_id='new-user'");
        assertThatThrownBy(() -> accounts.register("new-user", "password123", "123@qq.com", "123456")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT free_tokens FROM billing_wallet WHERE user_id='new-user'", Long.class)).isEqualTo(900000);
    }
    @Test void measuredInputAndOutputConsumeFreeCreditOnceWithoutMoney() {
        assertThatCode(() -> settle("chat", 600000, 200000)).doesNotThrowAnyException();
        assertThat(free()).isEqualTo(200000);
        billing.settle("alice", "chat", new Settlement(600000L, 200000L, "chat"));
        assertThat(free()).isEqualTo(200000);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(billing.wallet("alice").arrears()).isFalse();
        assertThat(jdbc.queryForObject("SELECT free_tokens_used FROM model_usage WHERE id='chat'", Long.class)).isEqualTo(800000);
    }
    @Test void warningStartsAtOneHundredThousandAndExhaustionIsNotDebt() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=100001 WHERE user_id='alice'");
        assertThat(billing.wallet("alice").lowBalance()).isFalse();
        jdbc.update("UPDATE billing_wallet SET free_tokens=100000 WHERE user_id='alice'");
        assertThat(billing.wallet("alice").lowBalance()).isTrue();
        settle("last", 90000, 10000);
        assertThat(free()).isZero();
        assertThat(billing.wallet("alice").arrears()).isFalse();
        assertThatThrownBy(() -> billing.reserve("alice", new Reserve("empty", "CHAT", "qwen-turbo"))).isInstanceOf(BillingException.class);
    }
    @Test void onlyOverflowTokensAreChargedAndDebtBlocksEveryPromotion() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=100 WHERE user_id='alice'");
        settle("overflow", 100, 100);
        assertThat(free()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(-1000);
        jdbc.update("UPDATE billing_wallet SET free_tokens=1000000,free_images=2 WHERE user_id='alice'");
        for (String capability : java.util.List.of("CHAT", "VISION", "IMAGE"))
            assertThatThrownBy(() -> billing.reserve("alice", new Reserve("debt-" + capability, capability, "model"))).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> groups.reserve("alice", "tenant", new GroupReserve("debt-group", "hash", "CHAT", true))).isInstanceOf(BillingException.class);
        assertThat(billing.wallet("alice").arrears()).isTrue();
    }
    @Test void cancellationUnknownUsageAndRefundNeverLoseOrDuplicateTokens() {
        billing.reserve("alice", new Reserve("cancel", "VISION", "model")); billing.cancel("alice", "cancel");
        assertThat(free()).isEqualTo(1000000);
        billing.reserve("alice", new Reserve("unknown", "VISION", "model")); billing.dispatch("alice", "unknown"); billing.unknown("alice", "unknown");
        assertThat(free()).isEqualTo(1000000);
        assertThatThrownBy(() -> billing.reserve("alice", new Reserve("again", "CHAT", "model"))).isInstanceOf(BillingException.class);
        billing.reconcileSettle("alice", "unknown", new Settlement(100L, 200L, "receipt"), "operator", "confirmed");
        assertThat(free()).isEqualTo(999700);
        billing.reconcileRefund("alice", "unknown", "operator", "confirmed refund");
        billing.reconcileRefund("alice", "unknown", "operator", "confirmed refund");
        assertThat(free()).isEqualTo(1000000);
        assertThat(billing.wallet("alice").freeImages()).isZero();
    }
    @Test void durableGroupsAuthorizeFreeTokensAndSettleOnlyTheWinnerOnce() {
        assertThatCode(() -> groups.reserve("alice", "tenant", new GroupReserve("group", "hash", "CHAT", true))).doesNotThrowAnyException();
        groups.attempt("alice", "tenant", "group", new AttemptReserve("PRIMARY", "QWEN", "qwen-turbo", "https://provider/v1", "key", 20000));
        groups.dispatch("alice", "tenant", "group", "PRIMARY");
        groups.receipt("alice", "tenant", "group", new AttemptReceipt("PRIMARY", "answer", 100L, 200L, "receipt"));
        groups.finish("alice", "tenant", "group"); groups.finish("alice", "tenant", "group");
        assertThat(free()).isEqualTo(999700);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(billing.wallet("alice").reservedMicros()).isZero();
        assertThat(billing.wallet("alice").activeUsageId()).isNull();
    }
    @Test void groupPartialAllowanceReservesAndChargesOnlyPaidRemainder() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=100,balance_micros=1000000 WHERE user_id='alice'");
        groups.reserve("alice", "tenant", new GroupReserve("partial", "hash", "CHAT", true));
        groups.attempt("alice", "tenant", "partial", new AttemptReserve("PRIMARY", "QWEN", "model", "https://provider/v1", "key", 30000));
        assertThat(billing.wallet("alice").reservedMicros()).isEqualTo(299000);
        groups.dispatch("alice", "tenant", "partial", "PRIMARY");
        groups.receipt("alice", "tenant", "partial", new AttemptReceipt("PRIMARY", "answer", 120L, 80L, "receipt"));
        groups.finish("alice", "tenant", "partial");
        assertThat(free()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(999000);
    }
    @Test void insufficientQuotaTicketCannotBlockAnEligibleAccount() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=1 WHERE user_id='alice'");
        assertThatThrownBy(() -> groups.reserve("alice", "tenant", new GroupReserve("poor", "hash", "CHAT", true)))
                .isInstanceOf(BillingException.class);
        jdbc.update("UPDATE model_call_group SET created_at=? WHERE id='poor'", java.time.LocalDateTime.now().minusMinutes(2));
        billing.createWallet("funded", 0, 1000000);
        assertThat(groups.reserve("funded", "tenant", new GroupReserve("funded", "hash", "CHAT", true)).status())
                .isEqualTo("RESERVED");
    }
    @Test void migratingExistingAccountsDoesNotIssueNewUserCredit() {
        billing.createWallet("existing", 1);
        assertThat(jdbc.queryForObject("SELECT free_tokens FROM billing_wallet WHERE user_id='existing'", Long.class)).isZero();
    }
}
