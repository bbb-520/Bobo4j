package com.bbb.exercise.agentdemo.common.web;

import com.bbb.exercise.agentdemo.common.trace.Correlation;
import com.bbb.exercise.agentdemo.common.trace.RequestId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 关联字段的入站入口（F14）。
 *
 * <p>每个请求在这里做三件事：解析（或生成）{@code requestId} 与 {@code traceId}、
 * 把它们写进响应头让客户端能对上号、写进 MDC 让后续日志带上。请求结束时清理 MDC——
 * Reactor 的线程是复用的，不清理就会把上一个请求的标识带到下一个请求的日志里，
 * 那种"串味"比没有字段更难查。</p>
 *
 * <p>排在最高优先级：后面的过滤器、鉴权失败、参数校验错误都应该带得上关联字段。</p>
 *
 * <p>为什么 {@code traceId} 不直接复用追踪组件生成的值：项目已引入 micrometer-tracing，
 * 但它的 traceId 生命周期由采样策略决定（未采样时可能没有），而访问日志需要<b>每个</b>请求
 * 都有可对账的标识。因此这里用"入站头 → 已有 traceId → 退化为 requestId"的顺序保证非空，
 * 追踪组件产生的 span 仍独立工作，两者不冲突。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId =
                RequestId.resolve(exchange.getRequest().getHeaders().getFirst(RequestId.HEADER));
        String traceId =
                Correlation.sanitize(
                        exchange.getRequest().getHeaders().getFirst(Correlation.TRACE_HEADER));

        if (traceId == null) {
            traceId = requestId;
        }

        // 回写响应头：用户报障时贴一个 requestId 就能定位到这条链路。
        exchange.getResponse().getHeaders().set(RequestId.HEADER, requestId);
        exchange.getResponse().getHeaders().set(Correlation.TRACE_HEADER, traceId);

        String resolvedTrace = traceId;

        return chain.filter(exchange)
                .doFirst(
                        () -> {
                            Correlation.put(Correlation.REQUEST_ID, requestId);
                            Correlation.put(Correlation.TRACE_ID, resolvedTrace);
                        })
                .doFinally(signal -> Correlation.clear());
    }
}
