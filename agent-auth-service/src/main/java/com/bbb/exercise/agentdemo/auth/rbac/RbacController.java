package com.bbb.exercise.agentdemo.auth.rbac;

import com.bbb.exercise.agentdemo.auth.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Supplier;

/** 权限管理入口，校验操作者权限后提供用户状态、角色授权及审计查询。 */
@RestController
@RequestMapping("/api/admin")
public class RbacController {
    private final AuthService auth;
    private final RbacService rbac;

    public RbacController(AuthService auth, RbacService rbac) {
        this.auth = auth;
        this.rbac = rbac;
    }

    // 1. 管理身份：从当前会话解析操作者，业务权限由授权服务进一步校验。
    private long actor(ServerWebExchange ex) {
        return auth.requireUserId(auth.resolve(ex));
    }

    private <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    // 2. 用户与授权查询：用户、角色、权限和审计使用各自的查询权限。
    @GetMapping("/users")
    public Mono<Map<String, Object>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            ServerWebExchange ex) {
        return blocking(() -> rbac.users(actor(ex), page, size));
    }

    @GetMapping("/roles")
    public Mono<List<Map<String, Object>>> roles(ServerWebExchange ex) {
        return blocking(() -> rbac.roles(actor(ex)));
    }

    @GetMapping("/permissions")
    public Mono<List<Map<String, Object>>> permissions(ServerWebExchange ex) {
        return blocking(() -> rbac.permissions(actor(ex)));
    }

    @GetMapping("/audits")
    public Mono<Map<String, Object>> audits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            ServerWebExchange ex) {
        return blocking(() -> rbac.audits(actor(ex), page, size));
    }

    // 3. 账户状态管理：把启停操作和原因交由授权服务处理。
    @PutMapping("/users/{user}/status")
    public Mono<Map<String, Boolean>> status(
            @PathVariable long user, @RequestBody Status body, ServerWebExchange ex) {
        return blocking(
                () -> {
                    rbac.setStatus(actor(ex), user, body.status(), body.reason());
                    return Map.of("updated", true);
                });
    }

    // 4. 用户角色管理：授权超级管理员时还需传递当前会话和再次验证密码。
    @PutMapping("/users/{user}/roles")
    public Mono<Map<String, Boolean>> role(
            @PathVariable long user, @RequestBody Grant body, ServerWebExchange ex) {
        return blocking(
                () -> {
                    rbac.setRole(
                            actor(ex),
                            user,
                            body.role(),
                            body.grant(),
                            body.reason(),
                            body.password(),
                            auth.sessionHash(ex));
                    return Map.of("updated", true);
                });
    }

    // 5. 角色配置管理：新增或更新角色及权限集合。
    @PutMapping("/roles/{code}")
    public Mono<Map<String, Boolean>> save(
            @PathVariable String code, @RequestBody Role body, ServerWebExchange ex) {
        return blocking(
                () -> {
                    rbac.saveRole(
                            actor(ex),
                            code,
                            body.name(),
                            body.status(),
                            body.permissions(),
                            body.reason());
                    return Map.of("updated", true);
                });
    }

    // 5.1. 角色删除入口：保留操作原因，复用服务层的内置角色保护。
    @DeleteMapping("/roles/{code}")
    public Mono<Map<String, Boolean>> delete(
            @PathVariable String code, @RequestBody Reason body, ServerWebExchange ex) {
        return blocking(
                () -> {
                    rbac.deleteRole(actor(ex), code, body.reason());
                    return Map.of("updated", true);
                });
    }

    /** 账户状态变更请求，记录目标状态和审计原因。 */
    public record Status(String status, String reason) {}

    /** 用户角色授予或撤销请求，携带审计原因及必要的密码确认。 */
    public record Grant(String role, boolean grant, String reason, String password) {
        @Override
        public String toString() {
            return "Grant[REDACTED]";
        }
    }

    /** 角色定义变更请求，携带名称、状态、权限集合及审计原因。 */
    public record Role(String name, String status, List<String> permissions, String reason) {}

    /** 角色删除等管理操作的审计原因。 */
    public record Reason(String reason) {}
}
