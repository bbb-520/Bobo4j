package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GalleryAccessTest {
    @Test void rejectsSpoofedHeadersAndRequiresTrustedBrowserSession() {
        var ex=MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-User-ID","admin").header("X-Roles","ADMIN"));
        assertThatThrownBy(()->GalleryAccess.authenticated(ex)).hasMessageContaining("401");
    }
    @Test void importerNeedsAdministrativeRoleAndModelManagementPermission() {
        var ex=MockServerWebExchange.from(MockServerHttpRequest.post("/"));
        ex.getAttributes().put(BrowserIdentityProvider.USER_ATTRIBUTE,new BrowserIdentityProvider.BrowserUser("user","local",Set.of("USER"),Set.of("model:manage")));
        GalleryAccess.authenticated(ex);
        assertThatThrownBy(()->GalleryAccess.importer(ex)).hasMessageContaining("403");
        ex.getAttributes().put(BrowserIdentityProvider.USER_ATTRIBUTE,new BrowserIdentityProvider.BrowserUser("admin","local",Set.of("ADMIN"),Set.of("model:manage")));
        assertThatCode(()->GalleryAccess.importer(ex)).doesNotThrowAnyException();
    }
}
