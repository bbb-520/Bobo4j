package com.bbb.exercise.agentdemo.mediaservice.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ImageJobHardeningTest {
    final ImageJobDatabase db = createDatabase();
    ImageJobDatabase createDatabase() { return new ImageJobDatabase(); }

    @Test void createdTaskAndExpiryUseDatabaseClock() {
        LocalDateTime before = db.now();
        String id = db.jobs.create(db.identity("alice"), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "draw", List.of()).jobId();
        LocalDateTime after = db.now();
        LocalDateTime created = db.jdbc.queryForObject("SELECT created_at FROM image_job WHERE id=?", LocalDateTime.class, id);
        assertThat(created).isBetween(before, after);
        assertThat(db.jdbc.queryForObject("SELECT expires_at FROM image_job WHERE id=?", LocalDateTime.class, id)).isEqualTo(created.plusDays(30));
        assertThat(db.jdbc.queryForObject("SELECT updated_at FROM image_job WHERE id=?", LocalDateTime.class, id)).isEqualTo(created);
    }

    @Test void successfulCompletionUsesDatabaseClock() {
        String id = db.insert("alice", "QUEUED", db.now().plusDays(1), null, true);
        db.jobs.claimNext("worker", Duration.ofMinutes(5));
        LocalDateTime before = db.now();
        assertThat(db.jobs.succeed(id, "worker", "output", "ok", "provider-id")).isTrue();
        LocalDateTime after = db.now();
        LocalDateTime completed = db.jdbc.queryForObject("SELECT completed_at FROM image_job WHERE id=?", LocalDateTime.class, id);
        assertThat(completed).isBetween(before, after);
        assertThat(db.jdbc.queryForObject("SELECT updated_at FROM image_job WHERE id=?", LocalDateTime.class, id)).isEqualTo(completed);
    }

    @Test void failedCompletionUsesDatabaseClock() {
        String id = db.insert("alice", "QUEUED", db.now().plusDays(1), null, false);
        db.jobs.claimNext("worker", Duration.ofMinutes(5));
        LocalDateTime before = db.now();
        assertThat(db.jobs.failWithoutReceipt(id, "worker", "failed", 1)).isTrue();
        LocalDateTime after = db.now();
        LocalDateTime completed = db.jdbc.queryForObject("SELECT completed_at FROM image_job WHERE id=?", LocalDateTime.class, id);
        assertThat(completed).isBetween(before, after);
        assertThat(db.jdbc.queryForObject("SELECT updated_at FROM image_job WHERE id=?", LocalDateTime.class, id)).isEqualTo(completed);
    }

    @ParameterizedTest @NullSource @ValueSource(ints = {-1, 0})
    void expiredProcessingWithoutLiveLeaseReachesTerminalState(Integer seconds) {
        String id = db.insert("alice", "PROCESSING", db.now().minusMinutes(1),
                seconds == null ? null : db.now().plusSeconds(seconds), false);
        assertThat(db.jobs.claimNext("new-worker", Duration.ofMinutes(5))).isNull();
        assertThat(db.status(id)).isEqualTo("FAILED");
        assertThat(db.jdbc.queryForObject("SELECT completed_at FROM image_job WHERE id=?", LocalDateTime.class, id)).isNotNull();
        assertThat(db.jdbc.queryForObject("SELECT lease_owner FROM image_job WHERE id=?", String.class, id)).isNull();
    }

    @Test void expiredTaskWithValidLeaseIsNeverTerminatedOrTakenOver() {
        String id = db.insert("alice", "PROCESSING", db.now().minusMinutes(1), db.now().plusMinutes(2), false);
        assertThat(db.jobs.claimNext("new-worker", Duration.ofMinutes(5))).isNull();
        assertThat(db.status(id)).isEqualTo("PROCESSING");
        assertThat(db.jobs.renewLease(id, "old-worker", Duration.ofMinutes(5))).isTrue();
    }

    @Test void expiredDurableReceiptCanStillBeClaimedForDelivery() {
        String id = db.insert("alice", "PROCESSING", db.now().minusMinutes(1), db.now().minusMinutes(2), true);
        assertThat(db.jobs.claimNext("new-worker", Duration.ofMinutes(5))).isNotNull().extracting(ImageJobService.JobRecord::id).isEqualTo(id);
        assertThat(db.jobs.receipt(db.identity("alice"), id).imageUrl()).isEqualTo("https://image");
        assertThat(db.jobs.succeed(id, "old-worker", "wrong", null, null)).isFalse();
        assertThat(db.jobs.succeed(id, "new-worker", "output", null, null)).isTrue();
    }

    @Test void lateReceiptRevivesExpiredTaskWithoutAllowingOldOwnerToCompleteIt() {
        String id = db.insert("alice", "PROCESSING", db.now().minusMinutes(1), null, false);
        db.jobs.claimNext("sweep", Duration.ofMinutes(5));
        db.jobs.recordResult(db.identity("mallory"), id, new ImageGenerationProvider.Result("https://wrong", "wrong", "wrong", 1L, 2L));
        assertThat(db.jobs.receipt(db.identity("alice"), id)).isNull();
        db.jobs.recordResult(db.identity("alice"), id, new ImageGenerationProvider.Result("https://late", "provider-id", "late", 10L, 20L));
        assertThat(db.jobs.claimNext("successor", Duration.ofMinutes(5))).isNotNull().extracting(ImageJobService.JobRecord::id).isEqualTo(id);
        assertThat(db.jobs.succeed(id, "old-worker", "wrong", null, null)).isFalse();
        assertThat(db.jobs.receipt(db.identity("alice"), id).inputTokens()).isEqualTo(10L);
    }

    @Test void missingLeaseBeforeExpiryIsRecoverable() {
        String id = db.insert("alice", "PROCESSING", db.now().plusDays(1), null, false);
        assertThat(db.jobs.claimNext("successor", Duration.ofMinutes(5))).isNotNull().extracting(ImageJobService.JobRecord::id).isEqualTo(id);
    }

    @Test void expiredTaskWithNoLeaseOwnerCannotRemainProcessing() {
        String id = db.insert("alice", "PROCESSING", db.now().minusMinutes(1), db.now().plusMinutes(3), false);
        db.jdbc.update("UPDATE image_job SET lease_owner=NULL WHERE id=?", id);
        assertThat(db.jobs.claimNext("successor", Duration.ofMinutes(5))).isNull();
        assertThat(db.status(id)).isEqualTo("FAILED");
    }

    @Test void anotherUsersTaskPassesOwnersBlockedSecondTask() {
        db.insert("alice", "PROCESSING", db.now().plusDays(1), db.now().plusMinutes(3), false);
        db.insert("alice", "QUEUED", db.now().plusDays(1), null, false);
        String bob = db.insert("bob", "QUEUED", db.now().plusDays(1), null, false);
        assertThat(db.jobs.claimNext("worker", Duration.ofMinutes(5))).isNotNull().extracting(ImageJobService.JobRecord::id).isEqualTo(bob);
    }

    @Test void pendingCapAllowsExistingRequestReplay() {
        String request = UUID.randomUUID().toString(), conversation = UUID.randomUUID().toString();
        String first = db.jobs.create(db.identity("alice"), request, conversation, "draw", List.of()).jobId();
        db.jobs.create(db.identity("alice"), UUID.randomUUID().toString(), conversation, "draw", List.of());
        assertThatThrownBy(() -> db.jobs.create(db.identity("alice"), UUID.randomUUID().toString(), conversation, "draw", List.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        assertThat(db.jobs.create(db.identity("alice"), request, conversation, "draw", List.of()).jobId()).isEqualTo(first);
    }

    @Test void concurrentOwnerAdmissionCannotExceedTwo() throws Exception {
        assertThat(admitConcurrently(12, false)).isEqualTo(2);
    }

    @Test void concurrentGlobalAdmissionCannotExceedTwenty() throws Exception {
        assertThat(admitConcurrently(30, true)).isEqualTo(20);
    }

    @Test void replicasRaceOnSameRequestAtCapWithoutRejectingReplay() throws Exception {
        String conversation = UUID.randomUUID().toString(), request = UUID.randomUUID().toString();
        db.jobs.create(db.identity("alice"), UUID.randomUUID().toString(), conversation, "draw", List.of());
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                var replica = db.newReplica();
                results.add(executor.submit(() -> { start.await(); return replica.create(db.identity("alice"), request, conversation, "draw", List.of()).jobId(); }));
            }
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM image_job", Integer.class)).isEqualTo(2);
        }
    }

    @Test void concurrentReplicasClaimEachJobOnceAndOnlyOneLiveJobPerOwner() throws Exception {
        db.insert("alice", "QUEUED", db.now().plusDays(1), null, false);
        db.insert("alice", "QUEUED", db.now().plusDays(1), null, false);
        db.insert("bob", "QUEUED", db.now().plusDays(1), null, false);
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ImageJobService.JobRecord>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                var replica = db.newReplica();
                String worker = "worker-" + i;
                results.add(executor.submit(() -> { start.await(); return replica.claimNext(worker, Duration.ofMinutes(5)); }));
            }
            start.countDown();
            List<ImageJobService.JobRecord> claimed = new ArrayList<>();
            for (var result : results) { var job = result.get(15, TimeUnit.SECONDS); if (job != null) claimed.add(job); }
            assertThat(claimed).hasSize(2).extracting(ImageJobService.JobRecord::userId).containsExactlyInAnyOrder("alice", "bob");
            assertThat(claimed).extracting(ImageJobService.JobRecord::id).doesNotHaveDuplicates();
            assertThat(db.jdbc.queryForObject("SELECT SUM(attempt_count) FROM image_job", Integer.class)).isEqualTo(2);
        }
    }

    int admitConcurrently(int count, boolean distinctUsers) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String user = distinctUsers ? "user-" + i : "alice";
                var replica = db.newReplica();
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        replica.create(db.identity(user), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "draw", List.of());
                        return true;
                    } catch (ResponseStatusException error) {
                        if (error.getStatusCode().value() != 429) throw error;
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++;
            return accepted;
        }
    }
}
