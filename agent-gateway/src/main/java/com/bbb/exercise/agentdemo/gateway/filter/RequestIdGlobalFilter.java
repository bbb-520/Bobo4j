package com.bbb.exercise.agentdemo.gateway.filter;

import com.bbb.exercise.agentdemo.common.trace.RequestId;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 网关请求追踪过滤器，为下游请求和响应统一设置请求 ID。 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {
    // 1. 请求关联：生成或规范化请求 ID，贯穿网关与下游日志。
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId =
                RequestId.resolve(exchange.getRequest().getHeaders().getFirst(RequestId.HEADER));
        ServerHttpRequest request =
                exchange.getRequest().mutate().header(RequestId.HEADER, requestId).build();

        exchange.getResponse().getHeaders().set(RequestId.HEADER, requestId);

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
