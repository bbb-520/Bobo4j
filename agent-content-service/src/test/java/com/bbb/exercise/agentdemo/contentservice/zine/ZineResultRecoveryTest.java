package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Reservation;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Wallet;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.BillingClient;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZineResultRecoveryTest {
    private final ChatIdentity alice = new ChatIdentity("local", "alice", true);
    private InternalServiceClient http;
    private JdbcTemplate jdbc;
    private ZineResultService results;

    @BeforeEach
    void setup() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__zine_generation_receipts.sql"))
                .execute(datasource);
        jdbc = new JdbcTemplate(datasource);
        http = mock(InternalServiceClient.class);
        results = new ZineResultService(jdbc, new BillingClient(http));
        when(http.postAuth(anyString(), anyString(), anyString(), any(), eq(Void.class)))
                .thenReturn(Mono.empty());
        when(http.postAuth(anyString(), anyString(), anyString(), any(), eq(Reservation.class)))
                .thenReturn(Mono.just(new Reservation("zine:one", true, 0, "RESERVED")));
    }

    @Test
    void aLostSettlementResponseCanBeRecoveredWithoutGeneratingAgain() {
        var generations = new AtomicInteger();
        var response = new ZineGenerationService.ZineGenerationResponse("poster", "qwen-image",
                "https://example.test/result.png", "result", "provider-1", 120L, 80L);
        when(http.postAuth(anyString(), anyString(), anyString(), any(), eq(Wallet.class)))
                .thenReturn(Mono.error(new IllegalStateException("lost response")))
                .thenReturn(Mono.just(new Wallet(0, 0, 1, false, true, false, null)));

        assertThatThrownBy(() -> results.generate(alice, "zine:one", "qwen-image",
                Mono.fromSupplier(() -> { generations.incrementAndGet(); return response; })).block())
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM zine_generation_receipt", Integer.class)).isOne();
        assertThat(results.get(alice, "zine:one").block().result()).isEqualTo(response);
        assertThat(generations).hasValue(1);
        assertThat(results.list(alice).block()).containsExactly("zine:one");
    }

    @Test
    void aDifferentUserOrTenantCannotReadOrSettleAReceipt() {
        jdbc.update("INSERT INTO zine_generation_receipt(id,tenant_id,user_id,response_json) VALUES (?,?,?,?)",
                "owned", "local", "alice", "{}");
        for (var other : new ChatIdentity[]{new ChatIdentity("local", "bob", true),
                new ChatIdentity("other-tenant", "alice", true)}) {
            assertThatThrownBy(() -> results.get(other, "owned").block())
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
            assertThat(results.list(other).block()).isEmpty();
        }
        verifyNoInteractions(http);
    }
}
