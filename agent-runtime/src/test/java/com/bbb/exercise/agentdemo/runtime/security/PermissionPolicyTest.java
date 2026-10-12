package com.bbb.exercise.agentdemo.runtime.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证已知业务路径精确映射到所需权限。 */
class PermissionPolicyTest {
    @Test void removedWechatAndPaymentOperatorRoutesAreNotPublic() {
        assertThat(PermissionPolicy.publicEndpoint("POST","/api/payments/callback/wechat")).isFalse();
        assertThat(PermissionPolicy.required("POST","/api/payments/admin/orders/id/refund/resolve")).isEqualTo("billing:refund");
        assertThat(PermissionPolicy.required("GET","/api/payments/admin/reconciliation")).isEqualTo("billing:reconcile");
        assertThat(PermissionPolicy.required("GET","/api/payments/admin/orders/id/audit")).isEqualTo("billing:reconcile");
    }
    @Test void albumCanBeReadAnonymouslyButHasNoPublicWriteOrSiblingPath() {
        assertThat(PermissionPolicy.publicEndpoint("GET", "/api/photo-archive")).isTrue();
        assertThat(PermissionPolicy.publicEndpoint("GET", "/api/photo-archive/a.jpg")).isTrue();
        assertThat(PermissionPolicy.publicEndpoint("POST", "/api/photo-archive")).isFalse();
        assertThat(PermissionPolicy.publicEndpoint("GET", "/api/photo-archive-private")).isFalse();
    }
    @Test
    void knownBusinessRoutesRequireTheirExactPermission() {
        assertThat(PermissionPolicy.required("POST", "/api/chat")).isEqualTo("chat:use");
        assertThat(PermissionPolicy.required("POST", "/api/documents"))
                .isEqualTo("rag:document:manage");
        assertThat(PermissionPolicy.required("POST", "/api/billing/admin/usages/user/id/refund"))
                .isEqualTo("billing:refund");
        assertThat(
                        PermissionPolicy.required(
                                "POST", "/api/billing/admin/model-groups/user/id/resolve-failure"))
                .isEqualTo("billing:reconcile");
        assertThat(PermissionPolicy.required("PUT", "/api/admin/users/1/roles"))
                .isEqualTo("role:assign");
        assertThat(PermissionPolicy.required("GET", "/api/unknown")).isEqualTo("DENY");
        assertThat(PermissionPolicy.required("POST", "/api/payments/admin/orders/id/refund"))
                .isEqualTo("billing:refund");
        assertThat(PermissionPolicy.required("GET", "/api/payments/admin/orders/id/refund"))
                .isEqualTo("billing:refund");
        assertThat(PermissionPolicy.required("GET", "/api/documents-forged")).isEqualTo("DENY");
    }
}
