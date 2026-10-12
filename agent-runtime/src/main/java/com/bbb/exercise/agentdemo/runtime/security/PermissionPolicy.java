package com.bbb.exercise.agentdemo.runtime.security;

import java.util.Set;

/** 浏览器接口权限策略，集中声明公共路径与业务路径所需权限。 */
public final class PermissionPolicy {
    private PermissionPolicy() {}

    // 1. 公共入口清单：集中列出无需浏览器业务身份的认证、健康和回调路径。
    public static boolean publicEndpoint(String method, String path) {
        if ("OPTIONS".equals(method)) {
            return true;
        }

        if ("GET".equals(method)) {
            return path.equals("/api/auth/csrf")
                    || under(path, "/api/photo-archive")
                    || path.equals("/actuator/health")
                    || path.startsWith("/actuator/health/")
                    || path.equals("/actuator/prometheus");
        }

        return "POST".equals(method)
                && Set.of(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/email/code",
                                "/api/auth/email/login",
                                "/api/payments/callback/alipay")
                        .contains(path);
    }

    private static boolean under(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }

    // 2. 路径权限映射：按业务域及请求方法选择所需权限，默认仍要求认证。
    public static String required(String method, String path) {
        if (path.equals("/api/auth/me") || path.equals("/api/auth/logout")) {
            return "AUTHENTICATED";
        }

        if (path.equals("/api/admin/users")) {
            return "user:read";
        }

        if (path.matches("/api/admin/users/[0-9]+/status")) {
            return "user:disable";
        }

        if (path.matches("/api/admin/users/[0-9]+/roles")) {
            return "role:assign";
        }

        if (path.equals("/api/admin/audits")) {
            return "audit:read";
        }

        if (path.equals("/api/admin/roles") && "GET".equals(method)) {
            return "role:assign";
        }

        if (under(path, "/api/admin/roles") || path.equals("/api/admin/permissions")) {
            return "role:permission:set";
        }

        if (under(path, "/api/billing/admin")) {
            return path.matches("/api/billing/admin/usages/[^/]+/[^/]+/refund")
                    ? "billing:refund"
                    : "billing:reconcile";
        }

        if (under(path, "/api/billing")) {
            return "wallet:read";
        }

        if (under(path, "/api/payments/orders")) {
            return "payment:manage";
        }

        if (path.matches("/api/payments/admin/orders/[^/]+/refund(?:/resolve)?")
                && Set.of("GET", "POST").contains(method)) {
            return "billing:refund";
        }

        if ("GET".equals(method) && (path.equals("/api/payments/admin/reconciliation")||path.matches("/api/payments/admin/orders/[^/]+/audit"))) return "billing:reconcile";

        if (path.equals("/api/auth/email/bind")) {
            return "profile:manage";
        }

        if (under(path, "/api/settings/models") || under(path, "/api/settings/keys")) {
            return "model:manage";
        }

        if (under(path, "/api/chat") || under(path, "/api/settings/visual-memory")) {
            return "chat:use";
        }

        if (under(path, "/api/documents") || under(path, "/api/document-conversations")) {
            return "rag:document:manage";
        }

        if (under(path, "/api/image-assets") || under(path, "/api/image-jobs")) {
            return "media:generate";
        }

        if (under(path, "/api/bobo") || under(path, "/api/zine")) {
            return "content:manage";
        }

        if (under(path, "/api/agent-runs") || under(path, "/api/agent-executions")) {
            return "agent:execute";
        }

        return "DENY";
    }
}
