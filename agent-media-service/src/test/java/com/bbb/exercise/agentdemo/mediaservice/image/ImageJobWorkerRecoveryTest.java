package com.bbb.exercise.agentdemo.mediaservice.image;
import com.bbb.exercise.agentdemo.runtime.client.*;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImageJobWorkerRecoveryTest {
    ImageJobService jobs=mock(ImageJobService.class);OssStorageService storage=mock(OssStorageService.class);
    AuthCredentialClient keys=mock(AuthCredentialClient.class);AuthModelClient models=mock(AuthModelClient.class);
    ImageGenerationProviderRegistry providers=mock(ImageGenerationProviderRegistry.class);BillingClient billing=mock(BillingClient.class);
    ImageJobWorker worker() {
        when(storage.isConfigured()).thenReturn(true);when(jobs.claimNext(anyString(),any())).thenReturn(new ImageJobService.JobRecord("job","local","alice","cid","source","output","gathered","chinese","prompt","PROCESSING","QWEN","qwen-image",null,null,null,LocalDateTime.now(),null,null));
        var worker=new ImageJobWorker(jobs,storage,keys,models,providers,billing);ReflectionTestUtils.setField(worker,"workerId","test");ReflectionTestUtils.setField(worker,"leaseDuration",Duration.ofMinutes(5));return worker;
    }
    @Test void persistedReceiptCanFinishAfterSettlementResponseLossWithoutCallingProvider() throws Exception {
        var worker=worker();when(jobs.receipt(any(),eq("job"))).thenReturn(new ImageJobService.Receipt("https://image",null,null,"rationale","provider-id"));
        when(billing.status(any(),eq("job"))).thenReturn(Mono.just(new BillingClient.CallStatus("SUCCEEDED")));
        when(billing.settle(any(),eq("job"),any())).thenReturn(Mono.just(new Wallet(100,0,0,false,true,false,null)));
        when(jobs.succeed(eq("job"),anyString(),eq("output"),eq("rationale"),eq("provider-id"))).thenReturn(true);
        worker.poll();
        verify(storage).copyRemoteImageToObject("https://image","output");verify(jobs).succeed(eq("job"),anyString(),eq("output"),eq("rationale"),eq("provider-id"));
        verifyNoInteractions(providers);verify(billing,never()).dispatch(any(),anyString());
    }
    @Test void refundedReceiptTerminatesAndReleasesQueueRatherThanRetryingForever() throws Exception {
        var worker=worker();when(jobs.receipt(any(),eq("job"))).thenReturn(new ImageJobService.Receipt("https://image",null,null,"rationale","provider-id"));
        when(billing.status(any(),eq("job"))).thenReturn(Mono.just(new BillingClient.CallStatus("REFUNDED")));
        worker.poll();verify(jobs).fail(eq("job"),anyString(),anyString(),eq(1));verify(jobs,never()).defer(anyString(),anyString());verify(storage,never()).copyRemoteImageToObject(anyString(),anyString());verifyNoInteractions(providers);
    }
    @Test void leaseSuccessorWaitsForDispatchedCallInsteadOfFailingOrGeneratingAgain() {
        var worker=worker();when(models.resolve(any(),eq(ModelCapability.IMAGE))).thenReturn(new AuthModelClient.SelectedModel(ModelProvider.QWEN,ModelCapability.IMAGE,"qwen-image","key"));
        when(billing.reserve(any(),eq("job"),eq("IMAGE"),eq("qwen-image"))).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT)));
        when(billing.status(any(),eq("job"))).thenReturn(Mono.just(new BillingClient.CallStatus("DISPATCHED")));
        worker.poll();verify(jobs).defer(eq("job"),anyString());verify(jobs,never()).fail(anyString(),anyString(),anyString(),anyInt());verifyNoInteractions(providers);
    }
}
