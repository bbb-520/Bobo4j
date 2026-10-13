package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class GalleryUsageGateTest {
    @Test void limitsCallsAndReleasesConcurrencyAfterFailure() throws Exception {
        var properties=new GalleryProperties();properties.setMaxConcurrent(1);properties.setMaxRequestsPerMinute(2);
        var gate=new GalleryUsageGate(properties,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        assertThatThrownBy(()->gate.execute(()->gate.execute(()->"nested"))).hasMessageContaining("429");
        assertThat(gate.execute(()->"next")).isEqualTo("next");
        assertThatThrownBy(()->gate.execute(()->"third")).hasMessageContaining("429");
    }
    @Test void rejectsUnboundedCostGuardConfiguration() {
        var properties=new GalleryProperties();properties.setMaxConcurrent(0);
        assertThatIllegalArgumentException().isThrownBy(()->new GalleryUsageGate(properties));
    }
}
