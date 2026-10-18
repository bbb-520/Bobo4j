package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Wallet;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.runtime.client.*;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQL fixture; only auth, credentials and object storage cross process boundaries. */
class ImageJobDatabase {
    final JdbcTemplate jdbc;
    final BillingClient billing = mock(BillingClient.class);
    final AuthModelClient models = mock(AuthModelClient.class);
    final OssStorageService storage = mock(OssStorageService.class);
    final ImageJobService jobs;

    ImageJobDatabase() {
        this(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")));
    }

    ImageJobDatabase(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    ImageJobDatabase(JdbcTemplate jdbc, boolean createTables) {
        this.jdbc = jdbc;
        if (createTables) {
        jdbc.execute("CREATE TABLE image_job (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(128),user_id VARCHAR(255),conversation_id VARCHAR(36),source_asset_id VARCHAR(36),source_object_key VARCHAR(512),output_object_key VARCHAR(512),mode VARCHAR(32),language VARCHAR(32),prompt TEXT,provider VARCHAR(32),model VARCHAR(128),rationale TEXT,status VARCHAR(32),provider_request_id VARCHAR(255),error_message TEXT,attempt_count INT DEFAULT 0,created_at TIMESTAMP(3),started_at TIMESTAMP(3),updated_at TIMESTAMP(3),completed_at TIMESTAMP(3),expires_at TIMESTAMP(3),lease_owner VARCHAR(128),lease_until TIMESTAMP(3),result_ready BOOLEAN DEFAULT FALSE,generated_url TEXT,input_tokens BIGINT,output_tokens BIGINT,next_retry_at TIMESTAMP(3),client_request_id VARCHAR(36),request_fingerprint VARCHAR(64),UNIQUE(tenant_id,user_id,client_request_id))");
        jdbc.execute("CREATE TABLE image_job_admission_guard (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO image_job_admission_guard(id) VALUES (1)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V7__zine_durable_jobs.sql"))
                .execute(jdbc.getDataSource());
        }
        when(models.resolve(any(), eq(ModelCapability.IMAGE))).thenReturn(
                new AuthModelClient.SelectedModel(ModelProvider.QWEN, ModelCapability.IMAGE, "qwen-image", "key"));
        when(billing.wallet(any())).thenReturn(Mono.just(new Wallet(100, 0, 0, false, true, false, null)));
        jobs = newReplica();
    }

    ImageJobService newReplica() {
        var replica = new ImageJobService(jdbc, mock(ImageAssetService.class), new OssProperties(), storage,
                mock(AuthCredentialClient.class), models, billing);
        replica.configureAdmission(new ImageJobAdmission(jdbc));
        return replica;
    }

    ChatIdentity identity(String user) { return new ChatIdentity("local", user, true); }

    LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class); }

    String insert(String user, String status, LocalDateTime expiry, LocalDateTime lease, boolean receipt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO image_job(id,tenant_id,user_id,conversation_id,prompt,mode,language,provider,model,status,created_at,updated_at,expires_at,lease_owner,lease_until,result_ready,output_object_key,generated_url) VALUES (?,'local',?,'conversation','draw','text-to-image','chinese','QWEN','qwen-image',?,?,?,?,?,?,?,'output',?)",
                id, user, status, now().minusHours(1), now(), expiry,
                "old-worker", lease, receipt, receipt ? "https://image" : null);
        return id;
    }

    String status(String id) { return jdbc.queryForObject("SELECT status FROM image_job WHERE id=?", String.class, id); }
}
