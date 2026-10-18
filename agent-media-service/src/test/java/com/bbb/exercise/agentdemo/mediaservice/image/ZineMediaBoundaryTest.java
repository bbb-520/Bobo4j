package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ZineMediaBoundaryTest {
    final String secrets="current=" + java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    final PrincipalKeyRing keys=new PrincipalKeyRing(secrets,"current");
    final ZineMediaJobService jobs=mock(ZineMediaJobService.class);
    final ZineMediaController controller=new ZineMediaController(jobs,secrets,"current");
    @Test void onlyContentServiceCanInvokePaidZineGeneration() {
        String token=keys.sign("agent-chat-service","alice","local","agent-media-service","POST /internal/media/zine/generate");
        assertThatThrownBy(() -> controller.generate(null,token)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jobs);
    }
    @Test void operationScopePreventsReusingAContentListSignatureForGeneration() {
        String token=keys.sign("agent-content-service","alice","local","agent-media-service","GET /internal/media/zine/generations");
        assertThatThrownBy(() -> controller.generate(null,token)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jobs);
    }
    @Test void contentCallerIdentityAndTenantAreForwardedFromVerifiedSignature() {
        String token=keys.sign("agent-content-service","alice","tenant-a","agent-media-service","POST /internal/media/zine/generate");
        when(jobs.generate(any(),any())).thenReturn(Mono.empty());
        controller.generate(null,token).block();
        verify(jobs).generate(eq(new com.bbb.exercise.agentdemo.api.identity.ChatIdentity("tenant-a","alice",true)),isNull());
    }
}
