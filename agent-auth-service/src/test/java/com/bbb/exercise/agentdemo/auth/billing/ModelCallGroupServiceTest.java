package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ModelCallGroupServiceTest {
    JdbcTemplate jdbc; ModelCallGroupService service; BillingService legacy;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"), new ClassPathResource("db/migration/V5__model_call_groups.sql"), new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        legacy = new BillingService(jdbc, tx, 10000, 200000, 1000000, 500000);
        legacy.createWallet("alice", 0);
        jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id='alice'");
        service = new ModelCallGroupService(jdbc, tx, 10000, 200000, true, 400000, 1000000, 3, 30);
    }
    GroupReserve request(String id, String hash) {return new GroupReserve(id,hash,"CHAT",true);}
    AttemptReserve primary() {return new AttemptReserve("PRIMARY","QWEN","qwen-turbo","https://provider/v1", "key1");}
    AttemptReserve fallback() {return new AttemptReserve("FALLBACK","QWEN","qwen-plus","https://provider/v1", "key1");}
    @Test void unknownPrimaryAllowsOnlyTheSameGroupsSingleFallbackAndReleasesForegroundAfterReceipt() {
        service.reserve("alice","tenant",request("call","hash")); service.attempt("alice","tenant","call",primary());
        service.dispatch("alice","tenant","call","PRIMARY"); service.unknown("alice","tenant","call","PRIMARY");
        assertThatThrownBy(() -> service.dispatch("alice","tenant","call","PRIMARY")).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service.reserve("alice","tenant",request("other","hash"))).isInstanceOf(BillingException.class);
        service.attempt("alice","tenant","call",fallback()); service.dispatch("alice","tenant","call","FALLBACK");
        var receipt = new AttemptReceipt("FALLBACK", "{\"text\":\"ok\"}", 120L,80L,"provider-1");
        service.receipt("alice","tenant","call",receipt); service.finish("alice","tenant","call"); service.finish("alice","tenant","call");
        assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(1998000);
        assertThat(legacy.wallet("alice").activeUsageId()).isNull();
        assertThat(service.reserve("alice","tenant",request("call","hash")).receipt()).isEqualTo("{\"text\":\"ok\"}");
        assertThat(jdbc.queryForObject("SELECT status FROM model_call_attempt WHERE group_id='call' AND role='PRIMARY'",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void changedParametersOrOwnerCannotReuseAReceipt() {
        service.reserve("alice","tenant",request("call","hash"));
        assertThatThrownBy(() -> service.reserve("alice","tenant",request("call","changed"))).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service.reserve("alice","other-tenant",request("call","hash"))).isInstanceOf(BillingException.class);
    }
    @Test void disabledOrExhaustedFaultPolicyNeverBypassesUnknown() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());
        service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        var disabled = new ModelCallGroupService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),10000,200000,false,400000,1000000,3,30);
        assertThatThrownBy(() -> disabled.attempt("alice","tenant","call",fallback())).isInstanceOf(BillingException.class);
        var exhausted = new ModelCallGroupService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),10000,200000,true,100000,1000000,3,30);
        assertThatThrownBy(() -> exhausted.attempt("alice","tenant","call",fallback())).isInstanceOf(BillingException.class);
    }
    @Test void crashAfterReceiptReplaysSettlementWithoutNewDispatchOrDoublePayment() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY");
        service.receipt("alice","tenant","call",new AttemptReceipt("PRIMARY","cached",100L,0L,"r"));
        assertThat(service.reserve("alice","tenant",request("call","hash")).receipt()).isEqualTo("cached");
        service.finish("alice","tenant","call"); service.finish("alice","tenant","call");
        assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(1999000);
        assertThatThrownBy(() -> service.dispatch("alice","tenant","call","PRIMARY")).isInstanceOf(BillingException.class);
    }
    @Test void identicalPrimaryAndFallbackConfigurationIsRejected() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        assertThatThrownBy(() -> service.attempt("alice","tenant","call",new AttemptReserve("FALLBACK","QWEN","qwen-turbo","https://provider/v1","key1"))).isInstanceOf(BillingException.class);
    }
    @Test void latePrimaryReceiptCannotReplaceTheWinnerAndPlatformReconciliationCannotCreditTwice() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        service.attempt("alice","tenant","call",fallback());service.dispatch("alice","tenant","call","FALLBACK");service.receipt("alice","tenant","call",new AttemptReceipt("FALLBACK","winner",100L,10L,"backup"));service.finish("alice","tenant","call");
        service.receipt("alice","tenant","call",new AttemptReceipt("PRIMARY","late",100L,20L,"primary"));
        assertThat(service.state("alice","tenant","call").receipt()).isEqualTo("winner");
        service.reconcile("alice","tenant","call","PRIMARY",1200,"operator","confirmed actual invoice");service.reconcile("alice","tenant","call","PRIMARY",1200,"operator","confirmed actual invoice");
        assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(1998900);
        assertThat(jdbc.queryForObject("SELECT allocated_micros FROM model_fault_budget WHERE id=1",Long.class)).isZero();
    }
    @Test void operatorCanCloseConfirmedNoChargeFailureAndReleaseWalletWithoutInventedUsage() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        service.resolveFailure("alice","tenant","call",0,"operator","supplier confirmed request rejected and no charge");
        assertThat(service.state("alice","tenant","call").status()).isEqualTo("FAILED_RESOLVED");assertThat(legacy.wallet("alice").activeUsageId()).isNull();
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_call_attempt WHERE group_id='call'",Long.class)).isNull();
        assertThatThrownBy(() -> service.dispatch("alice","tenant","call","PRIMARY")).isInstanceOf(BillingException.class);
    }
    @Test void confirmedFailureCanRestartTheSameCallWhileOldAttemptGenerationCannotSubmit() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY",1);service.unknown("alice","tenant","call","PRIMARY",1);
        service.resolveFailure("alice","tenant","call",0,"operator","supplier confirmed no charge");
        assertThat(service.reserve("alice","tenant",request("call","hash")).generation()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_attempt_history",Long.class)).isEqualTo(1);
        service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY",2);
        assertThatThrownBy(() -> service.receipt("alice","tenant","call",new AttemptReceipt("PRIMARY","old",100L,0L,"old",1))).isInstanceOf(BillingException.class);
        service.receipt("alice","tenant","call",new AttemptReceipt("PRIMARY","winner",100L,0L,"new",2));service.finish("alice","tenant","call");service.finish("alice","tenant","call");
        assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(1999000);
    }
    @Test void busyOwnersQueuedTicketDoesNotBlockOtherAccountsAdmission() {
        service.reserve("alice","tenant",request("running","hash"));service.attempt("alice","tenant","running",primary());service.dispatch("alice","tenant","running","PRIMARY");
        assertThat(service.reserve("alice","tenant",request("waiting","hash")).status()).isEqualTo("QUEUED");
        legacy.createWallet("bob",0);jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id='bob'");
        assertThat(service.reserve("bob","tenant",request("bob","hash")).status()).isEqualTo("RESERVED");
    }
    @Test void unresolvedTokensRemainConservativeAcrossRetryUntilSupplierConfirmsNoUsage() {
        var planned=new AttemptReserve("PRIMARY","QWEN","qwen-turbo","https://provider/v1","key1",4096);
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",planned);service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        assertThat(service.state("alice","tenant","call").unknownTokens()).isEqualTo(4096);
        service.resolveFailure("alice","tenant","call",0,"operator","failure confirmed but provider token usage unknown");service.reserve("alice","tenant",request("call","hash"));
        assertThat(service.state("alice","tenant","call").unknownTokens()).isEqualTo(4096);
        service.cancel("alice","tenant","call",2);service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",planned);service.dispatch("alice","tenant","call","PRIMARY",3);service.unknown("alice","tenant","call","PRIMARY",3);
        service.resolveFailure("alice","tenant","call",0,"operator","supplier confirmed generation 3 request not executed",true);
        assertThat(service.state("alice","tenant","call").unknownTokens()).isEqualTo(4096);
        assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(2000000);
    }
    @Test void cancellationReleasesOnlyUndispatchedHoldAndAllowsAuditedSameCallRestart() {
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",primary());service.cancel("alice","tenant","call",1);service.cancel("alice","tenant","call",1);
        assertThat(legacy.wallet("alice").reservedMicros()).isZero();assertThat(service.reserve("alice","tenant",request("call","hash")).generation()).isEqualTo(2);
        service.attempt("alice","tenant","call",primary());service.dispatch("alice","tenant","call","PRIMARY",2);assertThatThrownBy(() -> service.cancel("alice","tenant","call",2)).isInstanceOf(BillingException.class);
    }
    @Test void plannedSupplierUpperRaisesWalletReservationBeforeDispatchAndRejectsInsufficientBalance() {
        service.reserve("alice","tenant",request("call","hash"));
        service.attempt("alice","tenant","call",new AttemptReserve("PRIMARY","QWEN","qwen-turbo","https://provider/v1","key1",60000));
        assertThat(legacy.wallet("alice").reservedMicros()).isEqualTo(600000);
        service.cancel("alice","tenant","call",1);service.reserve("alice","tenant",request("huge","hash"));
        assertThatThrownBy(() -> service.attempt("alice","tenant","huge",new AttemptReserve("PRIMARY","QWEN","qwen-turbo","https://provider/v1","key1",300000))).isInstanceOf(BillingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_attempt WHERE group_id='huge'",Long.class)).isZero();assertThat(legacy.wallet("alice").reservedMicros()).isEqualTo(200000);
    }
    @Test void lateHistoricalUsageEvidenceRefreshesAccountingWithoutChangingReceiptOrChargingAgain() {
        var planned=new AttemptReserve("PRIMARY","QWEN","qwen-turbo","https://provider/v1","key1",4096);
        service.reserve("alice","tenant",request("call","hash"));service.attempt("alice","tenant","call",planned);service.dispatch("alice","tenant","call","PRIMARY");service.unknown("alice","tenant","call","PRIMARY");
        service.resolveFailure("alice","tenant","call",0,"operator","failure confirmed; token measurement pending");service.reserve("alice","tenant",request("call","hash"));
        service.attempt("alice","tenant","call",planned);service.dispatch("alice","tenant","call","PRIMARY",2);service.receipt("alice","tenant","call",new AttemptReceipt("PRIMARY","winner",100L,10L,"r",2));service.finish("alice","tenant","call");
        service.reconcileTokens("alice","tenant","call",1,"PRIMARY",120,3,"operator","supplier measured generation 1 input and output");service.reconcileTokens("alice","tenant","call",1,"PRIMARY",120,3,"operator","supplier measured generation 1 input and output");
        var state=service.state("alice","tenant","call");assertThat(state.unknownTokens()).isZero();assertThat(state.inputTokens()).isEqualTo(220);assertThat(state.outputTokens()).isEqualTo(13);assertThat(state.receipt()).isEqualTo("winner");assertThat(legacy.wallet("alice").balanceMicros()).isEqualTo(1998900);
        assertThatThrownBy(() -> service.reconcileTokens("alice","tenant","call",1,"PRIMARY",121,3,"operator","changed invoice")).isInstanceOf(BillingException.class);
    }
    @Test void foregroundTicketsWinThenAgedBackgroundBatchIsAdmittedBeforeFreshForeground() {
        for(int n=0;n<4;n++){String user=n==0?"alice":"active"+n;if(n>0){legacy.createWallet(user,0);jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id=?",user);}String id="run"+n;service.reserve(user,"tenant",request(id,"hash"));service.attempt(user,"tenant",id,primary());service.dispatch(user,"tenant",id,"PRIMARY");}
        for(String user:java.util.List.of("background","foreground","fresh")){legacy.createWallet(user,0);jdbc.update("UPDATE billing_wallet SET balance_micros=2000000 WHERE user_id=?",user);}
        assertThat(service.reserve("background","tenant",new GroupReserve("bg","hash","CHAT",false)).status()).isEqualTo("QUEUED");assertThat(service.reserve("foreground","tenant",request("fg","hash")).status()).isEqualTo("QUEUED");
        service.receipt("alice","tenant","run0",new AttemptReceipt("PRIMARY","done",100L,0L,"r"));service.finish("alice","tenant","run0");
        assertThat(service.reserve("background","tenant",new GroupReserve("bg","hash","CHAT",false)).status()).isEqualTo("QUEUED");assertThat(service.reserve("foreground","tenant",request("fg","hash")).status()).isEqualTo("RESERVED");service.cancel("foreground","tenant","fg",1);
        jdbc.update("UPDATE model_call_group SET created_at=TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) WHERE id='bg'");
        assertThat(service.reserve("fresh","tenant",request("new","hash")).status()).isEqualTo("QUEUED");assertThat(service.reserve("background","tenant",new GroupReserve("bg","hash","CHAT",false)).status()).isEqualTo("RESERVED");
    }
}
