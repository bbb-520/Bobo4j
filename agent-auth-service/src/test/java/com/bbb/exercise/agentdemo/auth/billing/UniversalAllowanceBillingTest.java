package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real-wallet regressions for measured usage and image-price allowance conversion. */
class UniversalAllowanceBillingTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    BillingService billing;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V3__billing.sql"),
                new ClassPathResource("db/migration/V5__model_call_groups.sql"),
                new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);
        billing = new BillingService(jdbc, tx, 10_000, 200_000, 1_000_000, 500_000);
        billing.createWallet("alice", 0, 1_000_000);
    }

    void dispatchImage(String id) {
        assertThatCode(() -> billing.reserve("alice", new Reserve(id, "IMAGE", "image-model")))
                .doesNotThrowAnyException();
        billing.dispatch("alice", id);
    }

    @Test
    void measuredImageUsesActualTokensWithNoCashAndNoImagePriceConversion() {
        assertThat(billing.reserve("alice", new Reserve("measured", "IMAGE", "image-model"))
                .reservedMicros()).isZero();
        billing.dispatch("alice", "measured");
        billing.settle("alice", "measured", new Settlement(120L, 80L, "provider-measured"));

        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(999_800);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        var usage = billing.usage("alice", 1).getFirst();
        assertThat(usage.inputTokens()).isEqualTo(120L);
        assertThat(usage.outputTokens()).isEqualTo(80L);
        assertThat(usage.metering()).isEqualTo("TOKEN");
        assertThat(jdbc.queryForObject(
                "SELECT free_tokens_used FROM model_usage WHERE id='measured'", Long.class))
                .isEqualTo(200);
    }

    @Test
    void usageReportsConsumedAllowanceSeparatelyFromActualProviderTokenCounts() {
        billing.reserve("alice", new Reserve("reported", "CHAT", "chat-model"));
        billing.dispatch("alice", "reported");
        billing.settle("alice", "reported", new Settlement(120L, 80L, "provider-reported"));

        var usageJson = JsonMapper.builder().build().valueToTree(billing.usage("alice", 1).getFirst());
        assertThat(usageJson.path("freeTokensUsed").asLong(-1)).isEqualTo(200);
        assertThat(usageJson.path("inputTokens").asLong()).isEqualTo(120);
        assertThat(usageJson.path("outputTokens").asLong()).isEqualTo(80);
    }

    @Test
    void unmeasuredImageUsesOneHundredThousandAllowanceWithoutFabricatingActualTokens() {
        dispatchImage("converted");
        billing.settle("alice", "converted", new Settlement(null, null, "provider-converted"));

        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(900_000);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(billing.wallet("alice").freeImages()).isZero();
        var usage = billing.usage("alice", 1).getFirst();
        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.metering()).isEqualTo("IMAGE_TOKEN");
        assertThat(usage.chargedMicros()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT free_tokens_used FROM model_usage WHERE id='converted'", Long.class))
                .isEqualTo(100_000);
    }

    @Test
    void imageConversionRoundsUpFractionalTokensUsingTheReservedRate() {
        billing = new BillingService(jdbc, tx, 7, 1, 1, 0);
        dispatchImage("rounded");
        // A later configuration change must not change an already reserved price or rate.
        billing = new BillingService(jdbc, tx, 10_000, 200_000, 1_000_000, 500_000);
        billing.settle("alice", "rounded", new Settlement(null, null, "provider-rounded"));

        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(999_857);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT free_tokens_used FROM model_usage WHERE id='rounded'", Long.class))
                .isEqualTo(143);
    }

    @Test
    void imageConversionDoesNotOverflowTheIntermediatePriceMultiplication() {
        billing = new BillingService(jdbc, tx, Long.MAX_VALUE, 1, Long.MAX_VALUE, 0);
        dispatchImage("large-price");
        billing.settle("alice", "large-price", new Settlement(null, null, "provider-large"));

        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(999_000);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
    }

    @Test
    void measuredImageOverflowChargesOnlyActualTokensBeyondTheAllowance() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=100 WHERE user_id='alice'");
        dispatchImage("measured-overflow");
        billing.settle("alice", "measured-overflow", new Settlement(120L, 80L, "provider-overflow"));

        assertThat(billing.wallet("alice").freeTokens()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(-1_000);
        assertThat(billing.usage("alice", 1).getFirst().metering()).isEqualTo("TOKEN");
    }

    @Test
    void partialImageAllowanceWithZeroCashPaysOnlyTheRemainderAndThenBlocksDebt() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=25001 WHERE user_id='alice'");
        assertThat(billing.reserve("alice", new Reserve("partial", "IMAGE", "image-model"))
                .reservedMicros()).isZero();
        billing.dispatch("alice", "partial");
        billing.settle("alice", "partial", new Settlement(null, null, "provider-partial"));

        assertThat(billing.wallet("alice").freeTokens()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(-749_990);
        assertThat(billing.wallet("alice").arrears()).isTrue();
        jdbc.update("UPDATE billing_wallet SET free_tokens=1000000,free_images=2 WHERE user_id='alice'");
        for (String capability : List.of("IMAGE", "CHAT", "VISION", "EMBEDDING", "RERANK", "EVALUATION")) {
            assertThatThrownBy(() -> billing.reserve("alice", new Reserve("debt-" + capability, capability, "model")))
                    .isInstanceOf(BillingException.class);
        }
    }

    @Test
    void partialImageCreditFloorsItsCashValueWithoutOvercreditingFractionalMicros() {
        billing = new BillingService(jdbc, tx, 7, 1, 2, 0);
        jdbc.update("UPDATE billing_wallet SET free_tokens=143,balance_micros=1 WHERE user_id='alice'");
        dispatchImage("fractional-credit");
        billing.settle("alice", "fractional-credit", new Settlement(null, null, "provider-fractional"));

        assertThat(billing.wallet("alice").freeTokens()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(billing.usage("alice", 1).getFirst().chargedMicros()).isEqualTo(1);
    }

    @Test
    void convertedImageSettlementAndRefundRestoreExactCreditAndCashOnlyOnce() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=25001,balance_micros=1000000 WHERE user_id='alice'");
        dispatchImage("refund");
        var receipt = new Settlement(null, null, "provider-refund");
        billing.settle("alice", "refund", receipt);
        billing.settle("alice", "refund", receipt);
        assertThat(billing.wallet("alice").freeTokens()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(250_010);
        assertThatThrownBy(() -> billing.settle("alice", "refund", new Settlement(null, null, "changed")))
                .isInstanceOf(BillingException.class);

        billing.reconcileRefund("alice", "refund", "operator", "provider confirmed refund");
        billing.reconcileRefund("alice", "refund", "operator", "provider confirmed refund");
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(25_001);
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(1_000_000);
        assertThat(billing.wallet("alice").freeImages()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_reconciliation", Long.class))
                .isEqualTo(1);
    }

    @Test
    void cancelledImageDoesNotConsumeAllowanceOrCreateLegacyImageTrials() {
        billing.reserve("alice", new Reserve("cancel", "IMAGE", "image-model"));
        billing.cancel("alice", "cancel");
        billing.cancel("alice", "cancel");

        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(1_000_000);
        assertThat(billing.wallet("alice").freeImages()).isZero();
        assertThat(billing.wallet("alice").reservedMicros()).isZero();
        assertThat(billing.wallet("alice").activeUsageId()).isNull();
    }

    @Test
    void failedImageReleasesAccountImmediatelyAndRetainsUnknownExposureWithoutInventingUsage() {
        dispatchImage("unknown");
        billing.unknown("alice", "unknown");
        billing.unknown("alice", "unknown");
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(1_000_000);
        assertThat(billing.wallet("alice").blocked()).isFalse();
        assertThat(billing.status("alice", "unknown")).isEqualTo("ABANDONED");
        assertThatCode(() -> billing.reserve("alice", new Reserve("next", "CHAT", "chat-model")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> billing.cancel("alice", "unknown"))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> billing.settle("alice", "unknown", new Settlement(null, null, "late")))
                .isInstanceOf(BillingException.class);
        billing.unknown("alice", "unknown");
        assertThat(billing.wallet("alice").activeUsageId()).isEqualTo("next");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_reconciliation WHERE usage_id='unknown' AND action='ABANDONED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM usage_reconciliation WHERE usage_id='unknown'", String.class)).contains("platform_estimated_exposure_micros=1000000");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_usage WHERE id='unknown'", Long.class)).isNull();
    }

    @Test
    void abandoningUnknownCashImageReleasesHoldOnlyOnceWithoutMintingCredit() {
        jdbc.update("UPDATE billing_wallet SET free_tokens=0,balance_micros=2000000 WHERE user_id='alice'");
        dispatchImage("unknown-refund");
        assertThat(billing.wallet("alice").reservedMicros()).isEqualTo(1000000);
        billing.unknown("alice", "unknown-refund");
        billing.unknown("alice", "unknown-refund");

        assertThat(billing.wallet("alice").freeTokens()).isZero();
        assertThat(billing.wallet("alice").balanceMicros()).isEqualTo(2000000);
        assertThat(billing.wallet("alice").reservedMicros()).isZero();
        assertThat(billing.wallet("alice").freeImages()).isZero();
        assertThat(billing.wallet("alice").activeUsageId()).isNull();
    }

    @Test
    void legacyImageTrialsTakePriorityOverMeasuredOrConvertedTokenAllowance() {
        jdbc.update("UPDATE billing_wallet SET free_images=2 WHERE user_id='alice'");
        dispatchImage("legacy-measured");
        billing.settle("alice", "legacy-measured", new Settlement(120L, 80L, "legacy-actual"));
        dispatchImage("legacy-unmeasured");
        billing.settle("alice", "legacy-unmeasured", new Settlement(null, null, "legacy-fixed"));

        assertThat(billing.wallet("alice").freeImages()).isZero();
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(1_000_000);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        billing.reconcileRefund("alice", "legacy-unmeasured", "operator", "provider confirmed refund");
        billing.reconcileRefund("alice", "legacy-unmeasured", "operator", "provider confirmed refund");
        assertThat(billing.wallet("alice").freeImages()).isEqualTo(1);
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(1_000_000);
    }

    @Test
    void documentCapabilitiesUseTheSameAllowanceWithoutCash() {
        for (String capability : List.of("EMBEDDING", "RERANK", "EVALUATION")) {
            billing.reserve("alice", new Reserve(capability, capability, "model"));
            billing.dispatch("alice", capability);
            billing.settle("alice", capability, new Settlement(100L, 20L, capability));
        }
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(999_640);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
    }

    @Test
    void durableDocumentGroupsShareTheAllowanceWithoutCash() {
        var groups = new ModelCallGroupService(jdbc, tx, 10_000, 200_000, true, 400_000, 1_000_000, 3, 30);
        for (String capability : List.of("EMBEDDING", "RERANK", "EVALUATION")) {
            String id = "group-" + capability;
            groups.reserve("alice", "tenant", new GroupReserve(id, "hash", capability, true));
            groups.attempt("alice", "tenant", id,
                    new AttemptReserve("PRIMARY", "QWEN", "model", "https://provider/v1", "key", 20_000));
            groups.dispatch("alice", "tenant", id, "PRIMARY");
            groups.receipt("alice", "tenant", id,
                    new AttemptReceipt("PRIMARY", "answer", 100L, 20L, capability));
            groups.finish("alice", "tenant", id);
        }
        assertThat(billing.wallet("alice").freeTokens()).isEqualTo(999_640);
        assertThat(billing.wallet("alice").balanceMicros()).isZero();
        assertThat(billing.wallet("alice").reservedMicros()).isZero();
    }
}
