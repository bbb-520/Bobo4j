package com.bbb.exercise.agentdemo.ragservice.gallery;

import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.*;

/** Requires the identity attached by the existing verified cookie authentication filter. */
public final class GalleryAccess {
    private GalleryAccess() {}
    public static BrowserIdentityProvider.BrowserUser authenticated(ServerWebExchange exchange) {
        Object identity=exchange.getAttribute(BrowserIdentityProvider.USER_ATTRIBUTE);
        if(!(identity instanceof BrowserIdentityProvider.BrowserUser user) || user.userId()==null || user.userId().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"A valid browser session is required");
        return user;
    }
    public static void importer(ServerWebExchange exchange) { modelOperator(exchange); }
    public static void modelOperator(ServerWebExchange exchange) {
        var user=authenticated(exchange);
        if(!user.permissions().contains("model:manage") || user.roles().stream().noneMatch(role->role.equals("ADMIN") || role.equals("SUPER_ADMIN")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Gallery model calls require ADMIN/SUPER_ADMIN and model:manage permission");
    }
}
