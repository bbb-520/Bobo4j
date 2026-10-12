package com.bbb.exercise.agentdemo.auth.rbac;

import com.bbb.exercise.agentdemo.auth.AuthService.AuthException;
import com.bbb.exercise.agentdemo.auth.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

/** Auth-owned authorization persistence. All mutations lock the tenant's SUPER_ADMIN role first. */
@Service
public class RbacService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PasswordHasher passwords;
    private final String tenant;
    private static final Set<String> SUPER_ONLY = Set.of("role:assign", "role:permission:set");

    public RbacService(
            JdbcTemplate db,
            TransactionTemplate tx,
            PasswordHasher passwords,
            @Value("${app.security.default-tenant-id:local}") String tenant) {
        this.db = db;
        this.tx = tx;
        this.passwords = passwords;
        this.tenant = tenant;
    }

    /** 账户当前有效的角色和权限集合，供身份解析和授权检查使用。 */
    public record Snapshot(Set<String> roles, Set<String> permissions) {}

    // 1. 授权快照：从当前有效角色计算权限，账户状态和角色缺失都不能被忽略。
    public Snapshot snapshot(long user) {
        active(user);
        var roles =
                new TreeSet<>(
                        db.queryForList(
                                "SELECT r.code FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.user_id=? AND ur.tenant_id=? AND r.status='ACTIVE'",
                                String.class,
                                user,
                                tenant));

        if (roles.isEmpty()) {
            throw new AuthException(403, "账户未配置有效角色");
        }

        var permissions =
                new TreeSet<>(
                        db.queryForList(
                                "SELECT DISTINCT p.code FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id JOIN app_role_permission rp ON rp.role_id=r.id JOIN app_permission p ON p.id=rp.permission_id WHERE ur.user_id=? AND ur.tenant_id=? AND r.status='ACTIVE'",
                                String.class,
                                user,
                                tenant));

        return new Snapshot(Set.copyOf(roles), Set.copyOf(permissions));
    }

    // 1.1. 权限校验：每次按最新快照判断，角色变更无需等待旧会话过期。
    public void require(long user, String permission) {
        if (!snapshot(user).permissions().contains(permission)) {
            throw new AuthException(403, "缺少权限：" + permission);
        }
    }

    public void active(long user) {
        var state = db.queryForList("SELECT status FROM app_user WHERE id=?", String.class, user);

        if (state.size() != 1 || !"ACTIVE".equals(state.getFirst())) {
            throw new AuthException(401, "账户不可用，请重新登录");
        }
    }

    // 2. 默认授权：注册事务内绑定 USER 角色，角色不可用时阻止注册完成。
    public void registerUser(long user) {
        // Joins the AccountService transaction; failure rolls back the user and wallet.
        var role = role("USER");

        if (!"ACTIVE".equals(role.get("status"))) {
            throw new AuthException(503, "默认角色不可用");
        }

        db.update(
                "INSERT INTO app_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                user,
                role.get("id"));
    }

    private Map<String, Object> role(String code) {
        var rows =
                db.queryForList(
                        "SELECT id,code,name,status,is_system FROM app_role WHERE tenant_id=? AND code=?",
                        tenant,
                        code);

        if (rows.size() != 1) {
            throw new AuthException(404, "角色不存在");
        }

        return rows.getFirst();
    }

    private boolean superUser(long user) {
        return db.queryForObject(
                        "SELECT COUNT(*) FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.tenant_id=? AND ur.user_id=? AND r.code='SUPER_ADMIN' AND r.status='ACTIVE'",
                        Integer.class,
                        tenant,
                        user)
                > 0;
    }

    // 2.1. 超级管理员校验：管理角色既要具备操作权限，也要持有超级管理员角色。
    private void requireSuper(long actor, String permission) {
        require(actor, permission);

        if (!superUser(actor)) {
            throw new AuthException(403, "仅超级管理员可管理角色");
        }
    }

    // 3. 管理变更串行化：先锁住租户的超级管理员角色，再执行角色或账户变更。
    private <T> T locked(Supplier<T> work) {
        return tx.execute(
                status -> {
                    db.queryForObject(
                            "SELECT id FROM app_role WHERE tenant_id=? AND code='SUPER_ADMIN' FOR UPDATE",
                            Long.class,
                            tenant);
                    return work.get();
                });
    }

    // 3.1. 管理员保底：禁止移除或禁用最后一位有效超级管理员。
    private void keepSuper(long target) {
        if (superUser(target)
                && db.queryForObject(
                                "SELECT COUNT(*) FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id JOIN app_user u ON u.id=ur.user_id WHERE ur.tenant_id=? AND r.code='SUPER_ADMIN' AND r.status='ACTIVE' AND u.status='ACTIVE'",
                                Integer.class,
                                tenant)
                        <= 1) {
            throw new AuthException(409, "必须保留至少一位有效超级管理员");
        }
    }

    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new AuthException(400, "请填写 1-500 字操作原因");
        }
    }

    // 3.2. 变更审计：要求操作原因，并保存操作者、目标及变更前后值。
    private void audit(
            Long actor, String target, String action, Object before, Object after, String why) {
        reason(why);
        db.update(
                "INSERT INTO authz_audit_log(tenant_id,actor_id,target,action,before_value,after_value,reason) VALUES(?,?,?,?,?,?,?)",
                tenant,
                actor,
                target,
                action,
                String.valueOf(before),
                String.valueOf(after),
                why.trim());
    }

    // 4. 首次管理员初始化：用户名、邮箱和账户状态必须同时匹配。
    public void bootstrap(String username, String email) {
        locked(
                () -> {
                    var users =
                            db.queryForList(
                                    "SELECT id,status,username,qq_email FROM app_user WHERE username=? AND qq_email=?",
                                    username,
                                    email);

                    if (users.size() != 1
                            || !username.equals(users.getFirst().get("username"))
                            || !email.equals(users.getFirst().get("qq_email"))
                            || !"ACTIVE".equals(users.getFirst().get("status"))) {
                        throw new AuthException(409, "指定账号不存在、邮箱不匹配或已禁用");
                    }

                    long user = ((Number) users.getFirst().get("id")).longValue();

                    if (superUser(user)) {
                        return null;
                    }

                    if (db.queryForObject(
                                    "SELECT COUNT(*) FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id JOIN app_user u ON u.id=ur.user_id WHERE ur.tenant_id=? AND r.code='SUPER_ADMIN' AND u.status='ACTIVE'",
                                    Integer.class,
                                    tenant)
                            > 0) {
                        throw new AuthException(409, "已有超级管理员，请通过管理接口授权");
                    }

                    var role = role("SUPER_ADMIN");

                    db.update(
                            "INSERT INTO app_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                            tenant,
                            user,
                            role.get("id"));
                    audit(
                            null,
                            String.valueOf(user),
                            "BOOTSTRAP_SUPER_ADMIN",
                            "USER",
                            "USER,SUPER_ADMIN",
                            "受控初始化，用户名及邮箱双重匹配");

                    return null;
                });
    }

    // 5. 用户角色变更：区分授权与撤销，并校验超级管理员授权的附加条件。
    public void setRole(
            long actor,
            long target,
            String code,
            boolean grant,
            String why,
            String password,
            String sessionHash) {
        reason(why);
        locked(
                () -> {
                    requireSuper(actor, "role:assign");
                    active(target);
                    var r = role(code);
                    String canonicalCode = (String) r.get("code");

                    if (grant && !"ACTIVE".equals(r.get("status"))) {
                        throw new AuthException(409, "角色已停用");
                    }

                    boolean exists =
                            db.queryForObject(
                                            "SELECT COUNT(*) FROM app_user_role WHERE tenant_id=? AND user_id=? AND role_id=?",
                                            Integer.class,
                                            tenant,
                                            target,
                                            r.get("id"))
                                    > 0;

                    if (exists == grant) {
                        return null;
                    }

                    if ("SUPER_ADMIN".equals(canonicalCode)) {
                        if (grant) {
                            var sessions =
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM auth_session WHERE user_id=? AND token_hash=? AND expires_at>CURRENT_TIMESTAMP",
                                            Integer.class,
                                            actor,
                                            sessionHash);
                            String hash =
                                    db.queryForObject(
                                            "SELECT password_hash FROM app_user WHERE id=?",
                                            String.class,
                                            actor);

                            if (sessions != 1
                                    || password == null
                                    || !passwords.matches(password, hash)) {
                                throw new AuthException(403, "授予超级管理员需使用当前会话再次验证密码");
                            }
                        } else {
                            keepSuper(target);
                        }
                    }

                    if (grant) {
                        db.update(
                                "INSERT INTO app_user_role(tenant_id,user_id,role_id,granted_by) VALUES(?,?,?,?)",
                                tenant,
                                target,
                                r.get("id"),
                                actor);
                    } else {
                        db.update(
                                "DELETE FROM app_user_role WHERE tenant_id=? AND user_id=? AND role_id=?",
                                tenant,
                                target,
                                r.get("id"));
                    }

                    audit(
                            actor,
                            String.valueOf(target),
                            grant ? "ROLE_GRANT" : "ROLE_REVOKE",
                            exists ? canonicalCode : "",
                            grant ? canonicalCode : "",
                            why);

                    return null;
                });
    }

    // 6. 账户启停：禁用时撤销已有会话，变更与管理员保底检查在同一事务执行。
    public void setStatus(long actor, long target, String status, String why) {
        reason(why);

        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new AuthException(400, "账户状态无效");
        }

        locked(
                () -> {
                    require(actor, "user:disable");
                    var old =
                            db.queryForList(
                                    "SELECT status FROM app_user WHERE id=? FOR UPDATE",
                                    String.class,
                                    target);

                    if (old.size() != 1) {
                        throw new AuthException(404, "用户不存在");
                    }

                    if (superUser(target) && !superUser(actor)) {
                        throw new AuthException(403, "不能修改超级管理员账户");
                    }

                    if (old.getFirst().equals(status)) {
                        return null;
                    }

                    if ("DISABLED".equals(status)) {
                        keepSuper(target);
                    }

                    db.update(
                            "UPDATE app_user SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                            status,
                            target);

                    if ("DISABLED".equals(status)) {
                        db.update("DELETE FROM auth_session WHERE user_id=?", target);
                    }

                    audit(
                            actor,
                            String.valueOf(target),
                            "USER_STATUS",
                            old.getFirst(),
                            status,
                            why);

                    return null;
                });
    }

    // 7. 角色配置：保护内置角色，并限制只有超级管理员可配置角色权限。
    public void saveRole(
            long actor,
            String code,
            String name,
            String status,
            List<String> permissions,
            String why) {
        reason(why);

        if (code == null
                || !code.matches("[A-Z][A-Z0-9_]{2,63}")
                || name == null
                || name.isBlank()
                || name.length() > 128
                || !Set.of("ACTIVE", "DISABLED").contains(status)
                || permissions == null
                || permissions.size() > 100) {
            throw new AuthException(400, "角色参数无效");
        }

        locked(
                () -> {
                    requireSuper(actor, "role:permission:set");
                    var rows =
                            db.queryForList(
                                    "SELECT id,is_system FROM app_role WHERE tenant_id=? AND code=?",
                                    tenant,
                                    code);

                    if (!rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst().get("is_system"))) {
                        throw new AuthException(409, "内置角色不可修改");
                    }

                    var valid =
                            new HashSet<>(
                                    db.queryForList(
                                            "SELECT code FROM app_permission", String.class));

                    if (!valid.containsAll(permissions)
                            || permissions.stream().anyMatch(SUPER_ONLY::contains)) {
                        throw new AuthException(400, "权限不存在或仅允许内置超级管理员持有");
                    }

                    String before =
                            rows.isEmpty() ? "" : role(code).toString() + rolePermissions(code);

                    if (rows.isEmpty()) {
                        db.update(
                                "INSERT INTO app_role(tenant_id,code,name,status) VALUES(?,?,?,?)",
                                tenant,
                                code,
                                name.trim(),
                                status);
                    } else {
                        db.update(
                                "UPDATE app_role SET name=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                                name.trim(),
                                status,
                                rows.getFirst().get("id"));
                    }

                    Object id = role(code).get("id");

                    db.update("DELETE FROM app_role_permission WHERE role_id=?", id);

                    for (String permission : new TreeSet<>(permissions)) {
                        db.update(
                                "INSERT INTO app_role_permission(role_id,permission_id) SELECT ?,id FROM app_permission WHERE code=?",
                                id,
                                permission);
                    }

                    audit(
                            actor,
                            code,
                            "ROLE_SAVE",
                            before,
                            role(code).toString() + rolePermissions(code),
                            why);

                    return null;
                });
    }

    // 7.1. 角色删除：校验角色属性及关联后执行清理，并保留审计记录。
    public void deleteRole(long actor, String code, String why) {
        reason(why);
        locked(
                () -> {
                    requireSuper(actor, "role:permission:set");
                    var r = role(code);

                    if (Boolean.TRUE.equals(r.get("is_system"))) {
                        throw new AuthException(409, "内置角色不可删除");
                    }

                    Object id = r.get("id");
                    String before = r.toString() + rolePermissions(code);

                    db.update(
                            "DELETE FROM app_user_role WHERE role_id=? AND tenant_id=?",
                            id,
                            tenant);
                    db.update("DELETE FROM app_role_permission WHERE role_id=?", id);
                    db.update("DELETE FROM app_role WHERE id=? AND tenant_id=?", id, tenant);
                    audit(actor, code, "ROLE_DELETE", before, "", why);

                    return null;
                });
    }

    private List<String> rolePermissions(String code) {
        return db.queryForList(
                "SELECT p.code FROM app_role r JOIN app_role_permission rp ON rp.role_id=r.id JOIN app_permission p ON p.id=rp.permission_id WHERE r.tenant_id=? AND r.code=? ORDER BY p.code",
                String.class,
                tenant,
                code);
    }

    // 8. 管理查询：返回角色及其权限，查询本身也需要对应管理权限。
    public List<Map<String, Object>> roles(long actor) {
        requireSuper(actor, "role:assign");
        var rows =
                db.queryForList(
                        "SELECT id,code,name,status,is_system FROM app_role WHERE tenant_id=? ORDER BY id",
                        tenant);

        rows.forEach(r -> r.put("permissions", rolePermissions((String) r.get("code"))));

        return rows;
    }

    public List<Map<String, Object>> permissions(long actor) {
        requireSuper(actor, "role:permission:set");
        return db.queryForList("SELECT code,name,module FROM app_permission ORDER BY module,code");
    }

    private static void page(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new AuthException(400, "分页参数无效");
        }
    }

    // 8.1. 用户分页：限制页大小，避免管理列表一次读取全部账户。
    public Map<String, Object> users(long actor, int page, int size) {
        require(actor, "user:read");
        page(page, size);
        var rows =
                db.queryForList(
                        "SELECT id,public_user_id,username,qq_email,status,created_at,last_login_at FROM app_user ORDER BY id LIMIT ? OFFSET ?",
                        size,
                        page * size);

        rows.forEach(
                r ->
                        r.put(
                                "roles",
                                db.queryForList(
                                        "SELECT r.code FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.tenant_id=? AND ur.user_id=? ORDER BY r.code",
                                        String.class,
                                        tenant,
                                        r.get("id"))));

        return Map.of(
                "items",
                rows,
                "total",
                db.queryForObject("SELECT COUNT(*) FROM app_user", Long.class),
                "page",
                page,
                "size",
                size);
    }

    // 8.2. 审计分页：按当前租户读取管理操作历史。
    public Map<String, Object> audits(long actor, int page, int size) {
        require(actor, "audit:read");
        page(page, size);

        return Map.of(
                "items",
                db.queryForList(
                        "SELECT id,actor_id,target,action,before_value,after_value,reason,created_at FROM authz_audit_log WHERE tenant_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                        tenant,
                        size,
                        page * size),
                "total",
                db.queryForObject(
                        "SELECT COUNT(*) FROM authz_audit_log WHERE tenant_id=?",
                        Long.class,
                        tenant),
                "page",
                page,
                "size",
                size);
    }

    // 9. 旧运营身份迁移：将历史白名单映射为现有角色授权。
    public void migrateLegacyAdmins(String ids) {
        if (ids == null || ids.isBlank()) {
            return;
        }

        locked(
                () -> {
                    for (String id : new TreeSet<>(Arrays.asList(ids.split(",")))) {
                        if (!id.trim().matches("[0-9a-f-]{36}")) {
                            continue;
                        }

                        var users =
                                db.queryForList(
                                        "SELECT id FROM app_user WHERE public_user_id=? AND status='ACTIVE'",
                                        Long.class,
                                        id.trim());

                        if (users.size() != 1) {
                            continue;
                        }

                        long user = users.getFirst();

                        // An explicit later revocation must survive service restarts.
                        if (db.queryForObject(
                                        "SELECT COUNT(*) FROM authz_audit_log WHERE tenant_id=? AND target=? AND action='LEGACY_BILLING_MIGRATION'",
                                        Integer.class,
                                        tenant,
                                        String.valueOf(user))
                                > 0) {
                            continue;
                        }

                        if (db.queryForObject(
                                        "SELECT COUNT(*) FROM app_role WHERE tenant_id=? AND code='LEGACY_BILLING_OPERATOR'",
                                        Integer.class,
                                        tenant)
                                == 0) {
                            db.update(
                                    "INSERT INTO app_role(tenant_id,code,name,is_system) VALUES(?,'LEGACY_BILLING_OPERATOR','原计费运营员',TRUE)",
                                    tenant);
                            db.update(
                                    "INSERT INTO app_role_permission SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p WHERE r.tenant_id=? AND r.code='LEGACY_BILLING_OPERATOR' AND p.code IN ('billing:refund','billing:reconcile')",
                                    tenant);
                        }

                        Object role = role("LEGACY_BILLING_OPERATOR").get("id");

                        if (db.queryForObject(
                                        "SELECT COUNT(*) FROM app_user_role WHERE tenant_id=? AND user_id=? AND role_id=?",
                                        Integer.class,
                                        tenant,
                                        user,
                                        role)
                                == 0) {
                            db.update(
                                    "INSERT INTO app_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                                    tenant,
                                    user,
                                    role);
                        }

                        audit(
                                null,
                                String.valueOf(user),
                                "LEGACY_BILLING_MIGRATION",
                                "",
                                "LEGACY_BILLING_OPERATOR",
                                "迁移已验证的计费运营白名单");
                    }

                    return null;
                });
    }
}
