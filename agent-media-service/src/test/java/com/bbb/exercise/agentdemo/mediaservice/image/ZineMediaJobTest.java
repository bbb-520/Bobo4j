package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ZineMediaJobTest {
    final ImageJobDatabase db = new ImageJobDatabase();
    final ChatIdentity alice = db.identity("alice");
    final String requestId = UUID.randomUUID().toString();
    ZineMediaJobService service() {
        return new ZineMediaJobService(db.jdbc, new ImageJobAdmission(db.jdbc), db.jobs, db.models);
    }
    InternalServiceClient.ZineRequest request(String prompt) {
        return new InternalServiceClient.ZineRequest(requestId, "data:image/png;base64,AQID", "poster", "chinese", prompt, "reason");
    }
    @Test void concurrentIdenticalRequestsAcrossReplicasCreateOneDurableJob() throws Exception {
        var first = service(); var second = service();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.create(alice, request("draw")); });
            var b = pool.submit(() -> { start.await(); return second.create(alice, request("draw")); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job", Integer.class)).isOne();
        assertThat(service().create(alice, request("draw"))).isEqualTo(service().create(alice, request("draw")));
    }
    @Test void sameRequestIdWithChangedPayloadConflicts() {
        service().create(alice, request("draw"));
        assertThatThrownBy(() -> service().create(alice, request("different")))
                .isInstanceOf(ResponseStatusException.class).satisfies(e ->
                        assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
    }
    @Test void identicalIdsAreIsolatedByOwnerAndTenant() {
        var first = service().create(alice, request("draw"));
        var second = service().create(db.identity("bob"), request("draw"));
        var third = service().create(new ChatIdentity("other", "alice", true), request("draw"));
        assertThat(first).isNotEqualTo(second).isNotEqualTo(third);
        assertThat(service().list(alice)).containsExactly(requestId);
        assertThatThrownBy(() -> service().get(db.identity("eve"), requestId))
                .isInstanceOf(ResponseStatusException.class).satisfies(e ->
                        assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
    }
    @Test void successfulResultReplaysOriginalReceiptWithoutCreatingAnotherJob() {
        var job = service().create(alice, request("draw"));
        db.jobs.recordResult(alice, job, new ImageGenerationProvider.Result("https://result", "supplier", "provider", 12L, 6L));
        db.jdbc.update("UPDATE image_job SET status='SUCCEEDED' WHERE id=?", job);
        var response = service().get(alice, requestId);
        assertThat(response.result().imageUrl()).isEqualTo("https://result");
        assertThat(response.result().rationale()).isEqualTo("reason");
        assertThat(service().create(alice, request("draw"))).isEqualTo(job);
        assertThat(service().get(alice, requestId)).isEqualTo(response);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job", Integer.class)).isOne();
        assertThat(db.jobs.archive(alice, 50)).as("Zine provider URLs must not appear as empty OSS image archive cards").isEmpty();
    }
    @Test void failedOrUnknownJobNeverCreatesNewPaidAttemptOnRetry() {
        var job = service().create(alice, request("draw"));
        db.jdbc.update("UPDATE image_job SET status='FAILED',provider_dispatch_started=TRUE WHERE id=?", job);
        assertThat(service().create(alice, request("draw"))).isEqualTo(job);
        assertThatThrownBy(() -> service().get(alice, requestId)).isInstanceOf(ResponseStatusException.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job", Integer.class)).isOne();
    }
}
