package com.bbb.exercise.agentdemo.mediaservice.worker;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WorkerLeaseServiceTest {
    @Test
    void onlyOneWorkerOwnsLease() {
        WorkerLeaseService service = new WorkerLeaseService();
        assertThat(service.tryAcquire("job-1", "worker-a", Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("job-1", "worker-b", Duration.ofMinutes(1))).isFalse();
        service.release("job-1", "worker-a");
        assertThat(service.tryAcquire("job-1", "worker-b", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void jdbcLeaseUsesConditionalUpdateAndDoesNotOverwriteTerminalJob() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1, 0);
        WorkerLeaseService service = new WorkerLeaseService(jdbc);

        assertThat(service.tryAcquire("job-1", "worker-a", Duration.ofMinutes(1))).isTrue();
        verify(jdbc).update(anyString(), eq("worker-a"), any(), eq("PROCESSING"), eq("job-1"), eq("worker-a"));
        assertThat(service.markSucceeded("job-1", "worker-a", "output/key.png")).isFalse();
    }

    @Test
    void jdbcLeaseDoesNotIncrementAttemptCountWhenClaimAlreadyCountedIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        WorkerLeaseService service = new WorkerLeaseService(jdbc);

        service.tryAcquire("job-1", "worker-a", Duration.ofMinutes(1));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("worker-a"), any(), eq("PROCESSING"), eq("job-1"), eq("worker-a"));
        assertThat(sql.getValue()).doesNotContain("attempt_count=attempt_count+1");
    }
}
