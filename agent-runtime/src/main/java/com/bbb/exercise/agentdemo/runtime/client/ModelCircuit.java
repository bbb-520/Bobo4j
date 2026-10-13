package com.bbb.exercise.agentdemo.runtime.client;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/** Redis window/open/probe keys use TTLs; local finite circuit remains available during Redis outages. */
@Component
public class ModelCircuit {
    private final ConcurrentHashMap<String, State> circuits = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final int failureThreshold,windowSeconds,openSeconds,probeSeconds;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final java.time.Clock clock;

    public ModelCircuit() {
        this(null,5,60,30,30,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),java.time.Clock.systemUTC());
    }

    @Autowired
    public ModelCircuit(
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis,
            @org.springframework.beans.factory.annotation.Value("${app.model.circuit.failure-threshold:5}") int threshold,
            @org.springframework.beans.factory.annotation.Value("${app.model.circuit.window-seconds:60}") int window,
            @org.springframework.beans.factory.annotation.Value("${app.model.circuit.open-seconds:30}") int open,
            @org.springframework.beans.factory.annotation.Value("${app.model.circuit.probe-seconds:30}") int probe,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this(redis.getIfAvailable(),threshold,window,open,probe,metrics,java.time.Clock.systemUTC());
    }
    public ModelCircuit(StringRedisTemplate redis,int threshold,int window,int open,int probe,io.micrometer.core.instrument.MeterRegistry metrics,java.time.Clock clock) {
        if(threshold<1||window<1||open<1||probe<1||window>86400||open>86400||probe>86400)throw new IllegalArgumentException("模型熔断配置无效");
        this.redis=redis;this.failureThreshold=threshold;this.windowSeconds=window;this.openSeconds=open;this.probeSeconds=probe;this.metrics=metrics;this.clock=clock;
    }

    // 1. 模型熔断准入：根据当前模型身份和熔断状态决定是否允许发起尝试。
    public boolean acquire(AuthModelClient.SelectedModel model) {
        if (redis != null) {
            try {
                Long permit =
                        redis.execute(
                                new DefaultRedisScript<>(
                                        "if redis.call('EXISTS',KEYS[1])==1 then return 0 end if redis.call('EXISTS',KEYS[2])==1 then if redis.call('SET',KEYS[3],'1','NX','EX',ARGV[1]) then return 1 else return 0 end end return 1",
                                        Long.class),
                                redisKeys(model),String.valueOf(probeSeconds));
                return admission(permit != null && permit == 1);
            } catch (RuntimeException ignored) {
                metrics.counter("ai.model.circuit.redis.errors").increment();
            }
        }

        var s = circuits.computeIfAbsent(key(model), k -> new State());

        synchronized (s) {
            long now = clock.millis();

            if (s.openUntil > now) {
                return admission(false);
            }

            if (s.openUntil != 0) {
                if (s.probeUntil > now) {
                    return admission(false);
                }

                s.probeUntil = now + probeSeconds*1000L;
            }

            return admission(true);
        }
    }

    // 2. 成功反馈：清理或恢复对应模型的熔断状态。
    public void success(AuthModelClient.SelectedModel model) {
        metrics.counter("ai.model.circuit.results","outcome","success").increment();
        if (redis != null) {
            try {
                redis.delete(redisKeys(model));
                redis.delete(redisKey(model) + ":failures");
            } catch (RuntimeException ignored) {
            }
        }

        var s = circuits.get(key(model));

        if (s != null) {
            synchronized (s) {
                s.failures.clear();
                s.openUntil = 0;
                s.probeUntil = 0;
            }
        }
    }

    // 3. 失败反馈：按可计入熔断的故障更新状态，减少持续请求不可用模型。
    public void failure(AuthModelClient.SelectedModel model) {
        metrics.counter("ai.model.circuit.results","outcome","failure").increment();
        if (redis != null) {
            try {
                var keys = new java.util.ArrayList<>(redisKeys(model));

                keys.add(redisKey(model) + ":failures");
                redis.execute(
                        new DefaultRedisScript<>(
                                "local n=redis.call('INCR',KEYS[4]); if n==1 then redis.call('EXPIRE',KEYS[4],ARGV[1]) end if n>=tonumber(ARGV[2]) or redis.call('EXISTS',KEYS[3])==1 then redis.call('SET',KEYS[1],'1','EX',ARGV[3]);redis.call('SET',KEYS[2],'1','EX',ARGV[4]);redis.call('DEL',KEYS[3]); end return n",
                                Long.class),
                        keys,String.valueOf(windowSeconds),String.valueOf(failureThreshold),String.valueOf(openSeconds),String.valueOf(openSeconds+Math.max(windowSeconds,probeSeconds)));
            } catch (RuntimeException ignored) {
            }
        }

        var s = circuits.computeIfAbsent(key(model), k -> new State());

        synchronized (s) {
            long now = clock.millis();

            while (!s.failures.isEmpty() && s.failures.peekFirst() < now - windowSeconds*1000L) {
                s.failures.removeFirst();
            }

            s.failures.addLast(now);

            if (s.probeUntil != 0 || s.failures.size() >= failureThreshold) {
                s.openUntil = now + openSeconds*1000L;
            }

            s.probeUntil = 0;
        }
    }

    private static String key(AuthModelClient.SelectedModel m) {
        return m.provider() + ":" + m.model() + ":" + m.baseUrl();
    }

    public double openCount() {
        long now = clock.millis();
        return circuits.values().stream().filter(s -> s.openUntil > now).count();
    }
    private boolean admission(boolean allowed){metrics.counter("ai.model.circuit.admission","outcome",allowed?"admitted":"blocked").increment();return allowed;}

    private static String redisKey(AuthModelClient.SelectedModel model) {
        return "ai:circuit:"
                + java.util.HexFormat.of()
                        .formatHex(
                                java.util
                                        .UUID
                                        .nameUUIDFromBytes(
                                                key(model)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static List<String> redisKeys(AuthModelClient.SelectedModel model) {
        String key = redisKey(model);
        return List.of(key + ":open", key + ":state", key + ":probe");
    }

    /** 单个模型路由的熔断状态，记录连续失败、熔断期限及半开探测占用。 */
    private static final class State {
        final ArrayDeque<Long> failures = new ArrayDeque<>();
        long openUntil;
        long probeUntil;
    }
}
