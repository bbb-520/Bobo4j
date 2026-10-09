package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 1（立即变红，无基线）：Gateway 是唯一公网业务入口，也是最外层的身份边界。
 *
 * <p>两条不可回退的约束：
 * <ol>
 *     <li>客户端自报的身份头一律不得穿透到下游——否则任何人都能伪造 {@code X-User-Id} 越权；</li>
 * </ol>
 *
 * <p>身份头部分做的是<b>行为验证</b>：直接构造请求、调用过滤器、观察改写后的结果。
 * {@code agent-gateway} 模块里有一份等价测试，这里再写一份是刻意的——
 * 本模块是门禁所在地，不允许"某个模块删掉自己的测试"就让约束从账本上消失。
 */
class GatewayAuthorizationBoundaryGateTest {

    /** 客户端可能伪造的身份头，全部必须被剥离。 */
    private static final List<String> FORGED_IDENTITY_HEADERS = List.of(
            "X-User-Id",
            "X-Tenant-Id",
            "X-Internal-Principal",
            "X-Principal",
            "X-Principal-User",
            "X-Principal-Signature",
            "x-principal-anything");

    private static final String SESSION_COOKIE = "bbb_agent_session";

    @Test
    @DisplayName("客户端自报的身份头全部被剥离，session Cookie 保留")
    void relayFilterStripsEveryClientSuppliedIdentityHeader() {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/chat/stream")
                .cookie(new HttpCookie(SESSION_COOKIE, "session-value"))
                .header("Accept", "text/event-stream")
                .header("Request-Id", "req-1");
        for (String header : FORGED_IDENTITY_HEADERS) {
            request.header(header, "forged");
        }

        ServerWebExchange forwarded = forward(MockServerWebExchange.from(request.build()));
        var headers = forwarded.getRequest().getHeaders();

        for (String header : FORGED_IDENTITY_HEADERS) {
            assertThat(headers.containsHeader(header))
                    .as("%s 是客户端自报身份，必须被 Gateway 剥离", header)
                    .isFalse();
        }
        assertThat(headers.headerNames())
                .as("任何以 x-principal 开头的身份头都不得穿透")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-principal"));

        assertThat(forwarded.getRequest().getCookies().containsKey(SESSION_COOKIE))
                .as("Cookie 必须保留：下游靠它做 session 校验，Gateway 自己不签发身份")
                .isTrue();
        assertThat(headers.containsHeader("Accept")).as("业务头不得被误删").isTrue();
        assertThat(headers.getFirst("Request-Id")).isEqualTo("req-1");
    }

    @Test
    @DisplayName("每个路由目标服务名都对应一个真实存在的模块目录")
    void everyRouteTargetResolvesToARealModule() {
        String source = Repository.text(gatewayRoutesSource());
        Matcher matcher = Pattern.compile(
                "static\\s+final\\s+String\\s+([A-Z_]*SERVICE_ID)\\s*=\\s*\"([^\"]+)\"").matcher(source);

        int serviceIds = 0;
        while (matcher.find()) {
            serviceIds++;
            assertThat(Repository.exists(matcher.group(2) + "/pom.xml"))
                    .as("%s 声明的服务名 %s 没有对应模块目录，路由会指向不存在的服务",
                            matcher.group(1), matcher.group(2))
                    .isTrue();
        }
        assertThat(serviceIds)
                .as("GatewayRoutes 必须用常量集中声明全部服务名")
                .isGreaterThanOrEqualTo(5);
    }

    private static ServerWebExchange forward(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = captured -> {
            forwarded.set(captured);
            return Mono.empty();
        };
        globalFilter().filter(exchange, chain).block();

        assertThat(forwarded.get()).as("过滤器必须把请求继续传给下游").isNotNull();
        return forwarded.get();
    }

    private static GlobalFilter globalFilter() {
        Object filter;
        try {
            Class<?> type = ModuleClasses.load("agent-gateway",
                    "com.bbb.exercise.agentdemo.gateway.filter.AuthRelayGlobalFilter");
            filter = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Gateway 身份中继过滤器不可实例化，身份边界无法验证", e);
        }
        assertThat(filter)
                .as("身份中继过滤器必须实现 GlobalFilter，否则它不会进入请求链路")
                .isInstanceOf(GlobalFilter.class);
        return (GlobalFilter) filter;
    }

    private static Path gatewayRoutesSource() {
        return Repository.resolve(
                "agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/config/GatewayRoutes.java");
    }

}
