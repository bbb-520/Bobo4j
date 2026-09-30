package com.bbb.exercise.agentdemo.mediaservice.worker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy compatibility helper retained for old unit consumers. Production
 * workers use ImageJobService.claimNext as the single atomic lease operation.
 */
public class WorkerLeaseService {
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final java.util.Set<String> terminalJobs = ConcurrentHashMap.newKeySet();
    private final JdbcTemplate jdbc;

    public WorkerLeaseService() {
        this.jdbc = null;
    }

    @Autowired
    public WorkerLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String jobId, String workerId, Duration duration) {
        if (jdbc != null) {
            long leaseMicros = Math.max(1, duration.toNanos() / 1_000L);
            int updated = jdbc.update("""
                    UPDATE image_job
                    SET lease_owner=?, lease_until=DATE_ADD(NOW(3), INTERVAL ? MICROSECOND),
                        status=?, updated_at=NOW(3)
                    WHERE id=? AND status IN ('QUEUED','PROCESSING')
                      AND (lease_owner=? OR lease_owner IS NULL OR lease_until IS NULL OR lease_until < NOW(3))
                    """, workerId, leaseMicros, "PROCESSING", jobId, workerId);
            return updated == 1;
        }
        if (terminalJobs.contains(jobId)) return false;
        Instant now = Instant.now();
        Instant until = now.plus(duration);
        return leases.compute(jobId, (key, current) -> {
            if (current == null || current.until().isBefore(now) || current.owner().equals(workerId)) {
                return new Lease(workerId, until);
            }
            return current;
        }).owner().equals(workerId);
    }

    public void release(String jobId, String workerId) {
        if (jdbc != null) {
            jdbc.update("UPDATE image_job SET lease_owner=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND lease_owner=?",
                    jobId, workerId);
            return;
        }
        leases.computeIfPresent(jobId, (key, lease) -> lease.owner().equals(workerId) ? null : lease);
    }

    public boolean markSucceeded(String jobId, String workerId, String outputObjectKey) {
        if (jdbc != null) {
            return jdbc.update("""
                    UPDATE image_job SET output_object_key=?, status='SUCCEEDED', completed_at=NOW(3),
                        lease_owner=NULL, lease_until=NULL, updated_at=NOW(3)
                    WHERE id=? AND lease_owner=? AND status NOT IN ('SUCCEEDED','FAILED')
                    """, outputObjectKey, jobId, workerId) == 1;
        }
        Lease lease = leases.get(jobId);
        if (lease == null || !lease.owner().equals(workerId) || terminalJobs.contains(jobId)) return false;
        terminalJobs.add(jobId);
        leases.remove(jobId, lease);
        return true;
    }

    private record Lease(String owner, Instant until) {
    }
}
