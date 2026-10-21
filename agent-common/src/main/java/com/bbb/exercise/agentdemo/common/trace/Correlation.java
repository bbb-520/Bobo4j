package com.bbb.exercise.agentdemo.common.trace;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关联字段（F14）：把一次请求、一个持久任务、一次远端调用串到同一条日志线上。
 *
 * <p>四个字段分工明确，不互相替代：</p>
 * <ul>
 *   <li>{@code requestId}：客户端可见的请求标识，网关生成并回写响应头，用于"用户报障时能对上号"；</li>
 *   <li>{@code traceId}：跨服务调用链标识，随出站请求头传播；</li>
 *   <li>{@code executionId}：持久执行任务标识，异步、跨线程、可能活很久，是任务维度的锚点；</li>
 *   <li>{@code callId}：一次计费/工具调用的标识，用于与账本、回执对账。</li>
 * </ul>
 *
 * <p>这里刻意只做"校验 + 写 MDC + 按固定顺序读出"，不注册全局 Reactor 钩子。
 * 自动传播看起来省事，但一旦某个 operator 没带上 context，日志会安静地少字段而不是报错。
 * 显式写入 + 显式清理至少能在测试里断言出来。</p>
 */
public final class Correlation {

    public static final String REQUEST_ID = "requestId";

    public static final String TRACE_ID = "traceId";

    public static final String EXECUTION_ID = "executionId";

    public static final String CALL_ID = "callId";

    /** 出站/入站头名。requestId 沿用既有约定，不另造一个。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    public static final String EXECUTION_HEADER = "X-Execution-Id";

    public static final String CALL_HEADER = "X-Call-Id";

    /** 与 {@code RequestId.resolve} 的上限一致：超长值一律丢弃，避免日志被注入撑爆。 */
    private static final int MAX_LENGTH = 128;

    /**
     * 允许的字符集。
     *
     * <p>限制成标识符安全集有两个原因：一是挡住换行与分隔符造成的日志注入；
     * 二是这些值会进日志与响应头，不该承载任何自由文本（更不该是提示词或文件内容）。</p>
     */
    private static final String SAFE = "[A-Za-z0-9_.:@+\\-]{1,128}";

    private Correlation() {}

    /** 校验并归一化。不合法返回 {@code null}，由调用方决定是丢弃还是补生成。 */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || !trimmed.matches(SAFE)) {
            return null;
        }

        return trimmed;
    }

    /** 写入 MDC；{@code null} 或非法值等价于清除该字段，不会留下上一次请求的残值。 */
    public static void put(String field, String value) {
        String safe = sanitize(value);

        if (safe == null) {
            MDC.remove(field);

            return;
        }

        MDC.put(field, safe);
    }

    /** 请求/任务边界结束时清理，防止线程复用时把上一个请求的标识带到下一个。 */
    public static void clear() {
        for (String field : fields()) {
            MDC.remove(field);
        }
    }

    /** 当前 MDC 中的关联字段快照（只含非空项，顺序固定）。 */
    public static Map<String, String> snapshot() {
        var values = new LinkedHashMap<String, String>();

        for (String field : fields()) {
            String value = MDC.get(field);

            if (value != null && !value.isBlank()) {
                values.put(field, value);
            }
        }

        return values;
    }

    /**
     * 固定顺序的 {@code k=v} 串，供日志消息追加。
     *
     * <p>顺序固定是为了可 grep、可断言：{@code requestId=... traceId=... executionId=... callId=...}。
     * 空值不输出，避免日志里出现一串 {@code callId=null}。</p>
     */
    public static String describe() {
        var text = new StringBuilder();

        snapshot()
                .forEach(
                        (field, value) -> {
                            if (!text.isEmpty()) {
                                text.append(' ');
                            }

                            text.append(field).append('=').append(value);
                        });

        return text.toString();
    }

    public static List<String> fields() {
        return List.of(REQUEST_ID, TRACE_ID, EXECUTION_ID, CALL_ID);
    }

    /**
     * 出站请求头：把当前 MDC 里的关联字段映射成头名。
     *
     * <p>只输出非空项——下游收不到某个头，就说明这一跳确实没有该维度的标识，
     * 比发一个空值更有诊断价值。返回 {@link Map} 而不直接依赖 Spring 的 {@code HttpHeaders}，
     * 这样 agent-common 的追踪工具在非 Web 场景（如定时任务）也能复用。</p>
     */
    public static Map<String, String> outgoingHeaders() {
        var headers = new LinkedHashMap<String, String>();
        var current = snapshot();

        if (current.containsKey(REQUEST_ID)) {
            headers.put(RequestId.HEADER, current.get(REQUEST_ID));
        }

        if (current.containsKey(TRACE_ID)) {
            headers.put(TRACE_HEADER, current.get(TRACE_ID));
        }

        if (current.containsKey(EXECUTION_ID)) {
            headers.put(EXECUTION_HEADER, current.get(EXECUTION_ID));
        }

        if (current.containsKey(CALL_ID)) {
            headers.put(CALL_HEADER, current.get(CALL_ID));
        }

        return headers;
    }
}
