package com.bbb.exercise.agentdemo.runtime.client;
import com.bbb.exercise.agentdemo.api.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class ModelCircuitConfigurationTest {
    @Test void abandonedHalfOpenProbeExpiresLocallyAndFailureRestartsCooldown() {
        var time=new MutableClock();var circuit=new ModelCircuit(null,1,60,5,2,new SimpleMeterRegistry(),time);
        var model=new AuthModelClient.SelectedModel(ModelProvider.QWEN,ModelCapability.CHAT,"probe","key","https://provider.test","credential");
        circuit.failure(model);time.now=time.now.plusSeconds(6);assertThat(circuit.acquire(model)).isTrue();
        assertThat(circuit.acquire(model)).isFalse();time.now=time.now.plusSeconds(3);
        assertThat(circuit.acquire(model)).isTrue();circuit.failure(model);assertThat(circuit.acquire(model)).isFalse();
        time.now=time.now.plusSeconds(6);assertThat(circuit.acquire(model)).isTrue();circuit.success(model);assertThat(circuit.acquire(model)).isTrue();
    }
    @Test void configuredThresholdAndCooldownAdmitOnlyOneProbeThenRecover() {
        var time=new MutableClock();var metrics=new SimpleMeterRegistry();
        var circuit=new ModelCircuit(null,2,60,5,2,metrics,time);
        var model=new AuthModelClient.SelectedModel(ModelProvider.QWEN,ModelCapability.CHAT,"model","key","https://provider.test","credential");
        circuit.failure(model);assertThat(circuit.acquire(model)).isTrue();
        circuit.failure(model);assertThat(circuit.acquire(model)).isFalse();
        time.now=time.now.plusSeconds(6);
        assertThat(circuit.acquire(model)).isTrue();assertThat(circuit.acquire(model)).isFalse();
        circuit.success(model);assertThat(circuit.acquire(model)).isTrue();
        assertThat(metrics.get("ai.model.circuit.admission").tag("outcome","blocked").counter().count()).isEqualTo(2);
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
