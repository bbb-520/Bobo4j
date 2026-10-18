package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import com.bbb.exercise.agentdemo.runtime.client.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ZineWorkerReliabilityTest {
    final ImageJobDatabase db = new ImageJobDatabase();
    final ImageGenerationProviderRegistry providers = mock(ImageGenerationProviderRegistry.class);
    final ImageGenerationProvider provider = mock(ImageGenerationProvider.class);
    final ZineMediaJobService zines = new ZineMediaJobService(db.jdbc,new ImageJobAdmission(db.jdbc),db.jobs,db.models);
    final String requestId=UUID.randomUUID().toString();
    final InternalServiceClient.ZineRequest request = new InternalServiceClient.ZineRequest(requestId,"data:image/png;base64,AQID","gathered","chinese","draw","reason");
    final ImageJobWorker worker;
    ZineWorkerReliabilityTest() {
        when(db.storage.isConfigured()).thenReturn(false);
        when(providers.resolve(ModelProvider.QWEN)).thenReturn(provider);
        when(db.billing.reserve(any(),anyString(),eq("IMAGE"),anyString()))
                .thenAnswer(call -> Mono.just(new Reservation(call.getArgument(1),false,0,"RESERVED")));
        when(db.billing.dispatch(any(),anyString())).thenReturn(Mono.empty());
        when(db.billing.unknown(any(),anyString())).thenReturn(Mono.empty());
        when(db.billing.status(any(),anyString())).thenReturn(Mono.just(new BillingClient.CallStatus("DISPATCHED")));
        when(db.billing.settle(any(),anyString(),any())).thenReturn(Mono.just(new Wallet(100,0,0,false,true,false,null)));
        when(provider.generate(any())).thenReturn(Mono.just(new ImageGenerationProvider.Result("https://image","supplier","provider reason",3L,2L)));
        worker=new ImageJobWorker(db.jobs,db.storage,mock(AuthCredentialClient.class),db.models,providers,db.billing);
        ReflectionTestUtils.setField(worker,"workerId","test"); ReflectionTestUtils.setField(worker,"leaseDuration",Duration.ofMinutes(1));
    }
    @Test void lostSettlementResponseRecoversSavedReceiptWithoutAnotherProviderDispatch() throws Exception {
        String id=zines.create(db.identity("alice"),request);
        when(db.billing.settle(any(),eq(id),any()))
                .thenReturn(Mono.error(new IllegalStateException("lost response")))
                .thenReturn(Mono.just(new Wallet(100,0,0,false,true,false,null)));
        worker.poll();
        assertThat(db.jobs.receipt(db.identity("alice"),id)).isNotNull();
        db.jdbc.update("UPDATE image_job SET next_retry_at=NULL WHERE id=?",id);
        worker.poll();
        assertThat(zines.get(db.identity("alice"),requestId).result().imageUrl()).isEqualTo("https://image");
        verify(provider,times(1)).generate(any()); verify(db.billing,times(1)).dispatch(any(),eq(id));
        verify(db.storage,never()).copyRemoteImageToObject(anyString(),anyString());
    }
    @Test void crashedWorkerAfterDurableDispatchFenceCannotRedispatchEvenIfBillingReserveWouldSucceed() {
        String id=zines.create(db.identity("alice"),request);
        db.jobs.claimNextZine("crashed",Duration.ofMinutes(1));
        assertThat(db.jobs.beginProviderDispatch(id,"crashed")).isTrue();
        db.jdbc.update("UPDATE image_job SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",id);
        worker.poll();
        assertThat(db.status(id)).isEqualTo("FAILED");
        assertThat(zines.create(db.identity("alice"),request)).isEqualTo(id);
        verifyNoInteractions(provider); verify(db.billing,never()).dispatch(any(),anyString());
    }
    @Test void unknownProviderOutcomeCannotBeRetriedWithTheSameClientId() {
        String id=zines.create(db.identity("alice"),request);
        when(provider.generate(any())).thenReturn(Mono.error(new IllegalStateException("provider response lost")));
        worker.poll();
        assertThat(db.status(id)).isEqualTo("FAILED");
        assertThat(zines.create(db.identity("alice"),request)).isEqualTo(id);
        worker.poll();
        verify(provider,times(1)).generate(any()); verify(db.billing,times(1)).dispatch(any(),eq(id));
    }
    @Test void cancellingHttpWaitDoesNotCancelDurableJobOrReceipt() {
        var subscription=zines.generate(db.identity("alice"),request).subscribe(v -> {}, e -> {});
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job",Integer.class)==1);
        } finally { subscription.dispose(); }
        worker.poll();
        assertThat(zines.get(db.identity("alice"),requestId).result().providerRequestId()).isEqualTo("supplier");
        verify(provider,times(1)).generate(any());
    }
    @Test void completedSourcePayloadIsCleanedButFingerprintStillDetectsChangedRetry() {
        String id=zines.create(db.identity("alice"),request); worker.poll(); zines.cleanSources();
        assertThat(db.jdbc.queryForObject("SELECT source_reference FROM zine_media_request WHERE job_id=?",String.class,id)).isNull();
        assertThat(zines.create(db.identity("alice"),request)).isEqualTo(id);
        var changed=new InternalServiceClient.ZineRequest(requestId,"data:image/png;base64,BAUG","gathered","chinese","draw","reason");
        assertThatThrownBy(() -> zines.create(db.identity("alice"),changed)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
