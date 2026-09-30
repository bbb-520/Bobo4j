package com.bbb.exercise.agentdemo1_0.bobo;

import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Retries private OSS object cleanup after deleted entries have been hidden from the public list. */
@Component
@ConditionalOnProperty(prefix = "app.bobo-world", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BoboWorldCleanupWorker {
    private final JdbcTemplate jdbc;
    private final OssStorageService storage;

    @Scheduled(fixedDelayString = "${app.bobo-world.cleanup-interval:60000}")
    public void cleanDeletedImages() {
        List<PendingObject> pending = jdbc.query("SELECT id,image_object_key FROM bobo_world_item "
                        + "WHERE status='DELETED' AND cleanup_pending=1 ORDER BY updated_at LIMIT 25",
                (rs, n) -> new PendingObject(rs.getString("id"), rs.getString("image_object_key")));
        if (pending.isEmpty()) return;
        Instant receivedAt = Instant.now();
        long startedNanos = System.nanoTime();
        log.info("Bobo World OSS cleanup batch started count={} receivedAt={}", pending.size(), receivedAt);
        for (PendingObject object : pending) {
            try {
                storage.deleteObject(object.objectKey());
                jdbc.update("UPDATE bobo_world_item SET cleanup_pending=0 WHERE id=? AND status='DELETED'",
                        object.itemId());
                log.info("Bobo World OSS object cleanup completed itemId={} completedAt={}",
                        object.itemId(), Instant.now());
            } catch (Exception e) {
                // Keep cleanup_pending set so a later schedule retries the operation.
                log.warn("Bobo World OSS object cleanup failed; will retry itemId={}", object.itemId(), e);
            }
        }
        log.info("Bobo World OSS cleanup batch finished count={} completedAt={} durationMs={}",
                pending.size(), Instant.now(), Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
    }

    private record PendingObject(String itemId, String objectKey) {}
}
