package com.bbb.exercise.agentdemo.runtime.security;

import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.*;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.*;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import reactor.core.publisher.Mono;
import java.util.Set;

/** 浏览器安全配置，装配会话认证、路径授权、CSRF 校验及安全过滤链。 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class BrowserSecurityConfiguration {
    // 1. 浏览器身份来源：默认经 Auth 查询有效会话和实时权限。
    @Bean
    @ConditionalOnMissingBean(BrowserIdentityProvider.class)
    BrowserIdentityProvider remoteBrowserIdentity(ObjectProvider<InternalServiceClient> clients) {
        return token ->
                clients.getObject()
                        .session(token)
                        .filter(InternalServiceClient.Identity::authenticated)
                        .map(
                                u ->
                                        new BrowserIdentityProvider.BrowserUser(
                                                u.userId(),
                                                u.tenantId(),
                                                u.roles(),
                                                u.permissions()));
    }

    // 2. 认证转换：将业务权限映射为 Spring Security 授权信息。
    @Bean
    ReactiveAuthenticationManager browserAuthenticationManager(BrowserIdentityProvider provider) {
        return authentication ->
                provider.session((String) authentication.getCredentials())
                        .switchIfEmpty(Mono.error(new BadCredentialsException("会话无效")))
                        .map(
                                user -> {
                                    var result =
                                            new UsernamePasswordAuthenticationToken(
                                                    user.userId(),
                                                    null,
                                                    user.permissions().stream()
                                                            .map(SimpleGrantedAuthority::new)
                                                            .toList());

                                    result.setDetails(user);

                                    return result;
                                });
    }

    // 3. 安全过滤链：组合 Cookie 认证、CSRF 和按路径授权。
    @Bean
    SecurityWebFilterChain browserSecurity(
            ServerHttpSecurity http,
            ReactiveAuthenticationManager manager,
            @Value("${app.security.session-cookie-name:bbb_agent_session}") String cookieName) {
        var filter = new AuthenticationWebFilter(manager);

        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        // 3.1. 会话提取：公共入口和内部签名接口分别走各自边界，其余请求从 Cookie 认证。
        filter.setServerAuthenticationConverter(
                exchange -> {
                    String path = exchange.getRequest().getPath().value();

                    if (path.startsWith("/internal/")
                            || PermissionPolicy.publicEndpoint(
                                    exchange.getRequest().getMethod().name(), path)) {
                        return Mono.empty();
                    }

                    var cookie = exchange.getRequest().getCookies().getFirst(cookieName);

                    return cookie == null
                            ? Mono.empty()
                            : Mono.just(
                                    new UsernamePasswordAuthenticationToken(
                                            "session", cookie.getValue()));
                });
        filter.setAuthenticationSuccessHandler(
                (web, authentication) -> {
                    web.getExchange()
                            .getAttributes()
                            .put(
                                    BrowserIdentityProvider.USER_ATTRIBUTE,
                                    authentication.getDetails());
                    return web.getChain().filter(web.getExchange());
                });
        filter.setAuthenticationFailureHandler(
                (web, error) -> {
                    web.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return web.getExchange().getResponse().setComplete();
                });
        // 3.2. CSRF 策略：浏览器写请求校验令牌，支付回调由渠道验签保护。
        var repository = CookieServerCsrfTokenRepository.withHttpOnlyFalse();

        repository.setCookiePath("/");

        return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(repository)
                                        .csrfTokenRequestHandler(
                                                new ServerCsrfTokenRequestAttributeHandler())
                                        .requireCsrfProtectionMatcher(
                                                exchange -> {
                                                    var request = exchange.getRequest();
                                                    String path = request.getPath().value();
                                                    boolean skip =
                                                            Set.of(
                                                                                    HttpMethod.GET,
                                                                                    HttpMethod.HEAD,
                                                                                    HttpMethod
                                                                                            .OPTIONS,
                                                                                    HttpMethod
                                                                                            .TRACE)
                                                                            .contains(
                                                                                    request
                                                                                            .getMethod())
                                                                    || path.startsWith("/internal/")
                                                                    || path.equals(
                                                                            "/api/payments/callback/alipay")
                                                                    || path.equals(
                                                                            "/api/payments/callback/wechat");

                                                    return skip
                                                            ? ServerWebExchangeMatcher.MatchResult
                                                                    .notMatch()
                                                            : ServerWebExchangeMatcher.MatchResult
                                                                    .match();
                                                }))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (exchange, error) -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(HttpStatus.UNAUTHORIZED);
                                                    return exchange.getResponse().setComplete();
                                                })
                                        .accessDeniedHandler(
                                                (exchange, error) -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(HttpStatus.FORBIDDEN);
                                                    return exchange.getResponse().setComplete();
                                                }))
                .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(
                        e ->
                                e.pathMatchers("/internal/**")
                                        .permitAll()
                                        .anyExchange()
                                        .access(
                                                (authentication, context) -> {
                                                    var request =
                                                            context.getExchange().getRequest();
                                                    String path = request.getPath().value();
                                                    String method = request.getMethod().name();

                                                    if (PermissionPolicy.publicEndpoint(
                                                            method, path)) {
                                                        return Mono.<AuthorizationResult>just(
                                                                new AuthorizationDecision(true));
                                                    }

                                                    String permission =
                                                            PermissionPolicy.required(method, path);

                                                    return authentication
                                                            .filter(
                                                                    a ->
                                                                            a.isAuthenticated()
                                                                                    && a
                                                                                                    .getDetails()
                                                                                            instanceof
                                                                                            BrowserIdentityProvider
                                                                                                    .BrowserUser)
                                                            .<AuthorizationResult>map(
                                                                    a ->
                                                                            new AuthorizationDecision(
                                                                                    "AUTHENTICATED"
                                                                                                    .equals(
                                                                                                            permission)
                                                                                            || a
                                                                                                    .getAuthorities()
                                                                                                    .stream()
                                                                                                    .anyMatch(
                                                                                                            p ->
                                                                                                                    p.getAuthority()
                                                                                                                            .equals(
                                                                                                                                    permission))))
                                                            .defaultIfEmpty(
                                                                    new AuthorizationDecision(
                                                                            false));
                                                }))
                .build();
    }
}
