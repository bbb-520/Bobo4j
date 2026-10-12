package com.bbb.exercise.agentdemo.auth.rbac;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 认证服务的浏览器身份配置，通过本地认证服务解析会话及当前权限。 */
@Configuration(proxyBeanMethods = false)
public class AuthBrowserIdentityConfiguration {
    @Bean
    @Primary
    BrowserIdentityProvider authBrowserIdentity(AuthService auth) {
        return token ->
                Mono.fromCallable(() -> auth.resolveSessionToken(token))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorMap(
                                AuthService.AuthException.class,
                                error ->
                                        new ResponseStatusException(
                                                HttpStatusCode.valueOf(error.status()),
                                                error.getMessage(),
                                                error))
                        .map(
                                u ->
                                        new BrowserIdentityProvider.BrowserUser(
                                                u.userId(),
                                                u.tenantId(),
                                                u.roles(),
                                                u.permissions()));
    }
}
