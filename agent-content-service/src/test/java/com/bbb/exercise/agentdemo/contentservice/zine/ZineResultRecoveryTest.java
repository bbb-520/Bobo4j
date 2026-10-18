package com.bbb.exercise.agentdemo.contentservice.zine;

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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZineResultRecoveryTest {
    final ChatIdentity alice = new ChatIdentity("local","alice",true);
    InternalServiceClient http;
    JdbcTemplate jdbc;
    ZineResultService results;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__zine_generation_receipts.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); http=mock(InternalServiceClient.class);
        results=new ZineResultService(jdbc,new BillingClient(http),http);
    }
    @Test void historicalReceiptRecoversLostSettlementWithoutCallingMedia() {
        jdbc.update("INSERT INTO zine_generation_receipt(id,tenant_id,user_id,response_json) VALUES ('zine:old','local','alice',?)",
                "{\"mode\":\"poster\",\"model\":\"qwen-image\",\"imageUrl\":\"https://result\",\"rationale\":\"reason\",\"providerRequestId\":\"provider\",\"inputTokens\":12,\"outputTokens\":8}");
        when(http.postAuth(anyString(),anyString(),anyString(),any(),eq(Wallet.class)))
                .thenReturn(Mono.error(new IllegalStateException("lost settlement response")))
                .thenReturn(Mono.just(new Wallet(0,0,1,false,true,false,null)));
        assertThatThrownBy(() -> results.get(alice,"zine:old").block()).isInstanceOf(RuntimeException.class);
        assertThat(results.get(alice,"zine:old").block().result().imageUrl()).isEqualTo("https://result");
        verify(http,never()).generateZine(anyString(),anyString(),any());
    }
    @Test void foreignHistoricalReceiptCannotBeReadOrSettled() {
        jdbc.update("INSERT INTO zine_generation_receipt(id,tenant_id,user_id,response_json) VALUES ('old','local','alice','{}')");
        for (var other : new ChatIdentity[]{new ChatIdentity("local","bob",true),new ChatIdentity("other","alice",true)})
            assertThatThrownBy(() -> results.get(other,"old").block()).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(http);
    }
}
