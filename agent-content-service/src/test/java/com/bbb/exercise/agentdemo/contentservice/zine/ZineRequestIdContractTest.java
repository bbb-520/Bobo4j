package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.contentservice.ZineCompositionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class ZineRequestIdContractTest {
    final String id="9d60c8ed-47ad-4a60-aaf0-467e204fe168";
    @Test void generateAcceptsStableClientRequestId() {
        var endpoint=Arrays.stream(ZineController.class.getMethods()).filter(m -> m.getName().equals("generate")).findFirst().orElseThrow();
        assertThat(Arrays.stream(endpoint.getParameters()).map(p -> p.getAnnotation(RequestPart.class))
                .filter(a -> a!=null).map(a -> a.name().isEmpty()?a.value():a.name())).contains("requestId");
    }
    @Test void multipartAndHeaderIdsBothSupportIdenticalRetries() {
        assertThat(ZineController.stableRequestId(id,null)).isEqualTo(id);
        assertThat(ZineController.stableRequestId(null,id)).isEqualTo(id);
        assertThat(ZineController.stableRequestId(id,id)).isEqualTo(id);
    }
    @Test void missingMalformedOrConflictingIdsFailBeforePaidWork() {
        assertThatThrownBy(() -> ZineController.stableRequestId(null,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ZineController.stableRequestId("random",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ZineController.stableRequestId(id,"949ed16c-2864-44d7-ad22-5a9b6194068f")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ZineController.stableRequestId(id.toUpperCase(),null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void deterministicCompositionKeepsClientIdAndIncludesGuidance() {
        var composer=new ZineGenerationService(new ZinePromptCompiler(),new ZineCompositionProperties());
        var first=composer.compose(id,new byte[]{1,2,3},"image/png","gathered","chinese","direction","guidance");
        assertThat(composer.compose(id,new byte[]{1,2,3},"image/png","gathered","chinese","direction","guidance")).isEqualTo(first);
        assertThat(first.requestId()).isEqualTo(id);
        assertThat(first.prompt()).contains("direction","guidance");
        assertThat(first.imageReference()).isEqualTo("data:image/png;base64,AQID");
    }
}
