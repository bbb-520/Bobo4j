package com.bbb.exercise.agentdemo1_0.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一记录 HTTP 请求的接收时间、结束时间和耗时，并把请求 ID 回传给调用方。
 *
 * <p>业务日志可以通过 {@link #REQUEST_ID_ATTRIBUTE} 关联到同一次请求；请求体不会被记录，
 * 避免把问题描述、图片信息或凭据写入日志。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingWebFilter implements WebFilter {

    public static final String REQUEST_ID_ATTRIBUTE = RequestTimingWebFilter.class.getName() + ".requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolveRequestId(exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER));
        Instant receivedAt = Instant.now();
        long startedNanos = System.nanoTime();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        log.info("[http] request_received requestId={} method={} path={} receivedAt={}",
                requestId, exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(), receivedAt);

        return chain.filter(exchange)
                .doOnError(failure::set)
                .doFinally(signal -> logCompletion(exchange, requestId, receivedAt, startedNanos,
                        signal.toString(), failure.get()));
    }

    private static void logCompletion(ServerWebExchange exchange, String requestId, Instant receivedAt,
                                      long startedNanos, String signal, Throwable failure) {
        Instant completedAt = Instant.now();
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (failure == null) {
            log.info("[http] request_completed requestId={} status={} receivedAt={} completedAt={} durationMs={} signal={}",
                    requestId, status == null ? 200 : status.value(), receivedAt, completedAt, durationMs, signal);
            return;
        }
        log.warn("[http] request_failed requestId={} status={} receivedAt={} completedAt={} durationMs={} signal={} errorType={} message={}",
                requestId, status == null ? 500 : status.value(), receivedAt, completedAt, durationMs, signal,
                failure.getClass().getSimpleName(), safeMessage(failure));
    }

    private static String resolveRequestId(String candidate) {
        if (candidate != null && candidate.length() <= MAX_REQUEST_ID_LENGTH
                && candidate.matches("[A-Za-z0-9._-]+")) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n]+", " ");
    }
}
