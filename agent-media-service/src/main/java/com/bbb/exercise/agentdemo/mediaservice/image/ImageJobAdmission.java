package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;
import java.util.function.Supplier;

/** One short database critical section, shared by all media replicas; never holds remote calls. */
@Component
public class ImageJobAdmission {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    @Value("${app.image-jobs.per-user-pending-limit:2}")
    private int perUserLimit = 2;
    @Value("${app.image-jobs.global-pending-limit:20}")
    private int globalLimit = 20;

    public ImageJobAdmission(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "image job datasource")));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public <T> T admit(ChatIdentity identity, Supplier<T> replay, Supplier<T> insert) {
        return locked(() -> {
            // A racing replay must succeed even if its winning insert filled the last slot.
            T existing = replay.get();
            if (existing != null) return existing;
            Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM image_job WHERE tenant_id=? AND user_id=? AND status IN ('QUEUED','PROCESSING')",
                    Integer.class, identity.tenantId(), identity.userId());
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM image_job WHERE status IN ('QUEUED','PROCESSING')", Integer.class);
            if (own >= Math.max(1, perUserLimit)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有图片任务排队或处理中，请等待完成后再试");
            }
            if (total >= Math.max(1, globalLimit)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "图片服务暂时繁忙，请稍后重试");
            }
            return insert.get();
        });
    }

    <T> T locked(Supplier<T> action) {
        return transaction.execute(status -> {
            Integer guard = jdbc.queryForObject("SELECT id FROM image_job_admission_guard WHERE id=1 FOR UPDATE", Integer.class);
            if (guard == null) throw new IllegalStateException("image admission guard missing");
            return action.get();
        });
    }
}
