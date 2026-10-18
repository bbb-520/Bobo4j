package com.bbb.exercise.agentdemo.auth.rbac;

import com.bbb.exercise.agentdemo.auth.PasswordHasher;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 验证权限初始化、授权撤销、超级管理员保护及审计事务的并发一致性。 */
class RbacServiceTest {
    JdbcTemplate db;
    RbacService rbac;
    PasswordHasher hasher = new PasswordHasher();

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa",
                        "");

        db = new JdbcTemplate(ds);
        // MySQL's deployed *_ci collation resolves role codes without case sensitivity.
        db.execute("SET IGNORECASE TRUE");
        db.execute(
                "CREATE TABLE app_user(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(64),qq_email VARCHAR(128),public_user_id VARCHAR(64),password_hash VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute(
                "CREATE TABLE auth_session(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,token_hash VARCHAR(64),expires_at TIMESTAMP,created_at TIMESTAMP)");
        db.update(
                "INSERT INTO app_user(id,username,qq_email,public_user_id,password_hash) VALUES(1,'bbb','475260611@qq.com','uuid-1',?),(2,'alice','2@qq.com','uuid-2',?)",
                hasher.hash("password123"),
                hasher.hash("password123"));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__rbac.sql"))
                .execute(ds);
        rbac =
                new RbacService(
                        db,
                        new TransactionTemplate(new DataSourceTransactionManager(ds)),
                        hasher,
                        "local");
    }

    @Test
    void migrationBackfillsUserWithoutChangingIdentity() {
        assertThat(rbac.snapshot(1).roles()).containsExactly("USER");
        assertThat(rbac.snapshot(1).permissions())
                .contains("chat:use")
                .doesNotContain("role:assign");
        assertThat(
                        db.queryForObject(
                                "SELECT public_user_id FROM app_user WHERE id=1", String.class))
                .isEqualTo("uuid-1");
        assertThatThrownBy(() -> new RbacService(db, null, hasher, "other").snapshot(1))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void bootstrapRequiresBothFieldsAndIsIdempotentOnlyForSameAccount() {
        assertThatThrownBy(() -> rbac.bootstrap("bbb", "wrong@qq.com"))
                .isInstanceOf(RuntimeException.class);
        rbac.bootstrap("bbb", "475260611@qq.com");
        rbac.bootstrap("bbb", "475260611@qq.com");
        assertThat(rbac.snapshot(1).roles()).containsExactlyInAnyOrder("USER", "SUPER_ADMIN");
        assertThatThrownBy(() -> rbac.bootstrap("alice", "2@qq.com"))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM authz_audit_log", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void changesNeedAuthorityAndPasswordAndRevokeOnNextRead() {
        assertThatThrownBy(() -> rbac.setRole(2, 1, "ADMIN", true, "test", null, null))
                .isInstanceOf(RuntimeException.class);
        rbac.bootstrap("bbb", "475260611@qq.com");
        assertThatThrownBy(() -> rbac.setRole(1, 2, "SUPER_ADMIN", true, "test", "wrong", null))
                .isInstanceOf(RuntimeException.class);
        rbac.setRole(1, 2, "ADMIN", true, "test", null, null);
        assertThat(rbac.snapshot(2).permissions())
                .contains("billing:refund")
                .doesNotContain("role:assign");
        rbac.setRole(1, 2, "ADMIN", false, "revoke", null, null);
        assertThat(rbac.snapshot(2).permissions()).doesNotContain("billing:refund");
        assertThatThrownBy(() -> rbac.setStatus(2, 1, "DISABLED", "test"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void disableRevokesSessionsAndLastSuperCannotBeRemoved() {
        rbac.bootstrap("bbb", "475260611@qq.com");
        assertThatThrownBy(() -> rbac.setStatus(1, 1, "DISABLED", "test"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rbac.setRole(1, 1, "SUPER_ADMIN", false, "test", null, null))
                .isInstanceOf(RuntimeException.class);
        db.update(
                "INSERT INTO auth_session(user_id,token_hash,expires_at) VALUES(2,'token',CURRENT_TIMESTAMP)");
        rbac.setStatus(1, 2, "DISABLED", "test");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_session WHERE user_id=2", Integer.class))
                .isZero();
        assertThatThrownBy(() -> rbac.snapshot(2)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void customRoleCannotAcquireSuperManagementPermissions() {
        rbac.bootstrap("bbb", "475260611@qq.com");
        rbac.saveRole(1, "SUPPORT", "客服", "ACTIVE", List.of("user:read"), "create");
        rbac.setRole(1, 2, "SUPPORT", true, "grant", null, null);
        assertThat(rbac.snapshot(2).permissions()).contains("user:read");
        assertThatThrownBy(
                        () ->
                                rbac.saveRole(
                                        1,
                                        "SUPPORT",
                                        "客服",
                                        "ACTIVE",
                                        List.of("role:assign"),
                                        "bad"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rbac.saveRole(1, "USER", "用户", "DISABLED", List.of(), "bad"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void legacyAllowlistIsValidatedAndGrantsOnlyBillingPermissionsOnce() {
        rbac.migrateLegacyAdmins("forged,missing,uuid-2");
        assertThat(rbac.snapshot(2).roles()).containsExactly("USER");
        db.update(
                "UPDATE app_user SET public_user_id='ad26db5c-7e4d-39bd-87b3-e1991585dca2' WHERE id=2");
        rbac.migrateLegacyAdmins("ad26db5c-7e4d-39bd-87b3-e1991585dca2");
        rbac.migrateLegacyAdmins("ad26db5c-7e4d-39bd-87b3-e1991585dca2");
        assertThat(rbac.snapshot(2).permissions())
                .contains("billing:refund", "billing:reconcile")
                .doesNotContain("role:assign", "user:disable");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM authz_audit_log", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void superGrantNeedsPasswordAndTheSpecificCurrentSession() {
        rbac.bootstrap("bbb", "475260611@qq.com");
        db.update(
                "INSERT INTO auth_session(user_id,token_hash,expires_at) VALUES(1,'current-hash',DATEADD('DAY',1,CURRENT_TIMESTAMP))");
        assertThatThrownBy(
                        () ->
                                rbac.setRole(
                                        1,
                                        2,
                                        "SUPER_ADMIN",
                                        true,
                                        "grant",
                                        "password123",
                                        "wrong-hash"))
                .isInstanceOf(RuntimeException.class);
        rbac.setRole(1, 2, "SUPER_ADMIN", true, "grant", "password123", "current-hash");
        assertThat(rbac.snapshot(2).roles()).contains("SUPER_ADMIN", "USER");
    }

    @Test
    void caseInsensitiveRoleLookupCannotBypassSuperGuards() {
        rbac.bootstrap("bbb", "475260611@qq.com");
        assertThatThrownBy(() -> rbac.setRole(1, 2, "super_admin", true, "case bypass", null, null))
                .isInstanceOfSatisfying(
                        com.bbb.exercise.agentdemo.auth.AuthService.AuthException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
        assertThat(rbac.snapshot(2).roles()).doesNotContain("SUPER_ADMIN");
        assertThatThrownBy(
                        () -> rbac.setRole(1, 1, "Super_Admin", false, "case bypass", null, null))
                .isInstanceOfSatisfying(
                        com.bbb.exercise.agentdemo.auth.AuthService.AuthException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
        assertThat(rbac.snapshot(1).roles()).contains("SUPER_ADMIN");
    }

    @Test
    void legacyMigrationDoesNotRegrantAfterExplicitRevocationAndRestart() {
        String uuid = "ad26db5c-7e4d-39bd-87b3-e1991585dca2";

        db.update("UPDATE app_user SET public_user_id=? WHERE id=2", uuid);
        rbac.migrateLegacyAdmins(uuid);
        rbac.bootstrap("bbb", "475260611@qq.com");
        rbac.setRole(1, 2, "LEGACY_BILLING_OPERATOR", false, "remove old privilege", null, null);
        rbac.migrateLegacyAdmins(uuid);
        assertThat(rbac.snapshot(2).roles()).containsExactly("USER");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM authz_audit_log WHERE action='LEGACY_BILLING_MIGRATION'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackRoleMutation() {
        rbac.bootstrap("bbb", "475260611@qq.com");
        db.execute("DROP TABLE authz_audit_log");
        assertThatThrownBy(() -> rbac.setRole(1, 2, "ADMIN", true, "test", null, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(rbac.snapshot(2).roles()).containsExactly("USER");
    }

    @Test
    void concurrentRevocationsLeaveOneActiveSuper() throws Exception {
        rbac.bootstrap("bbb", "475260611@qq.com");
        db.update(
                "INSERT INTO app_user_role(tenant_id,user_id,role_id,granted_by) SELECT 'local',2,id,1 FROM app_role WHERE code='SUPER_ADMIN'");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);

        try {
            List<Future<?>> tasks = new ArrayList<>();

            for (long target : List.of(1L, 2L)) {
                tasks.add(
                        pool.submit(
                                () -> {
                                    try {
                                        gate.await();
                                        rbac.setRole(
                                                target,
                                                target,
                                                "SUPER_ADMIN",
                                                false,
                                                "concurrent",
                                                null,
                                                null);
                                    } catch (RuntimeException expected) {
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }

            gate.countDown();

            for (var f : tasks) {
                f.get(15, TimeUnit.SECONDS);
            }

            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id WHERE r.code='SUPER_ADMIN'",
                                    Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
