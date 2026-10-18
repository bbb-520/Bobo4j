package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.DefiniteProviderFailure;
import com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.ProviderConnectFailure;
import static com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.endpoint;
import static com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.token;
import static com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.protocol;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Blocking gateway for boundedElastic/workers. Every dispatched attempt is durable and single-use. */
@Component
public class ModelCallClient {
    private final AuthModelClient models;
    private final BillingClient billing;
    private final ModelProviderTransport transport;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration total, backupTotal;
    private final ModelCircuit circuit;
    private MeterRegistry metrics = new SimpleMeterRegistry();

    public ModelCallClient(AuthModelClient models, BillingClient billing, WebClient.Builder http) {
        this(models, billing, http, null, 15, 20, 60, 30);
    }

    public ModelCallClient(
            AuthModelClient models,
            BillingClient billing,
            WebClient.Builder http,
            ModelCircuit circuit,
            int first,
            int idle,
            int total,
            int backupTotal) {
        this.models = models;
        this.billing = billing;
        this.circuit = circuit;

        if (first <= 0 || idle <= 0 || total <= 0 || backupTotal <= 0) {
            throw new IllegalArgumentException("模型超时配置无效");
        }

        this.transport = new ModelProviderTransport(
                // Paid requests use fresh sockets; transport retries bypass durable accounting.
                http.clone().clientConnector(new ReactorClientHttpConnector(
                        HttpClient.newConnection().disableRetry(true)
                                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)))
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16_000_000)).build(),
                json, Duration.ofSeconds(first), Duration.ofSeconds(idle));
        this.total = Duration.ofSeconds(Math.min(total, 60));
        this.backupTotal = Duration.ofSeconds(Math.min(backupTotal, 30));
    }

    @Autowired
    public ModelCallClient(
            AuthModelClient models,
            BillingClient billing,
            WebClient.Builder http,
            ModelCircuit circuit,
            @Value(
                            "${app.model.first-output-timeout-seconds:${MODEL_FIRST_OUTPUT_TIMEOUT_SECONDS:15}}")
                    int first,
            @Value("${app.model.idle-timeout-seconds:${MODEL_IDLE_TIMEOUT_SECONDS:20}}") int idle,
            @Value("${app.model.total-timeout-seconds:${MODEL_TOTAL_TIMEOUT_SECONDS:60}}")
                    int total,
            @Value("${app.model.fallback-timeout-seconds:${MODEL_FALLBACK_TIMEOUT_SECONDS:30}}")
                    int backupTotal,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> registry) {
        this(models, billing, http, circuit, first, idle, total, backupTotal);
        this.metrics = registry.getIfAvailable(SimpleMeterRegistry::new);
        io.micrometer.core.instrument.Gauge.builder(
                        "ai.model.circuit.local.open", circuit, c -> c.openCount())
                .register(metrics);
    }

    // 1. 生成入口：同步、流式和视觉请求都进入统一的调用组生命周期。
    /** Recover an owned durable result without admitting or dispatching another model attempt. */
    public Optional<Completion> recoverCompletion(ChatIdentity identity, String callId) {
        if (identity == null
                || !identity.authenticated()
                || callId == null
                || !callId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("模型调用身份或 ID 无效");
        }

        checkCancelled();
        GroupState group;

        try {
            group = billing.group(identity, callId).block(Duration.ofSeconds(6));
        } catch (org.springframework.web.server.ResponseStatusException error) {
            if (error.getStatusCode().value() == 404) {
                throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
            }

            throw error;
        }

        if (group == null) {
            throw protocol("BILLING_UNAVAILABLE");
        }

        if("ABANDONED".equals(group.status()))throw new ModelCallException("MODEL_CALL_INTERRUPTED",false,400);
        if (group.receipt() != null) {
            return Optional.of(replay(identity, callId, group, Completion.class));
        }

        if (group.unknownTokens() > 0
                || !Set.of("QUEUED", "RESERVED", "CANCELLED", "FAILED_RESOLVED")
                        .contains(group.status())) {
            throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
        }

        if (Set.of("QUEUED", "RESERVED").contains(group.status())) {
            try {
                billing.cancelGroup(identity, callId, group.generation())
                        .block(Duration.ofSeconds(6));
            } catch (org.springframework.web.server.ResponseStatusException error) {
                if (error.getStatusCode().value() == 409) {
                    throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
                }

                throw error;
            }
        }

        return Optional.empty();
    }

    /** fallback means allowFallback; EVALUATION and DECISION never silently change their judge/schema. */
    public Completion complete(
            ChatIdentity identity,
            String callId,
            String mode,
            String system,
            String user,
            boolean fallback) {
        return completeStreaming(identity, callId, mode, system, user, fallback, event -> {});
    }

    public Completion complete(
            ChatIdentity identity,
            String callId,
            String mode,
            String system,
            String user,
            boolean fallback,
            Long tokenBudget) {
        return completeStreaming(
                identity, callId, mode, system, user, fallback, tokenBudget, event -> {});
    }

    public Completion completeStreaming(
            ChatIdentity identity,
            String callId,
            String mode,
            String system,
            String user,
            boolean fallback,
            Consumer<GenerationEvent> events) {
        return completeStreaming(identity, callId, mode, system, user, fallback, null, events);
    }

    public Completion completeStreaming(
            ChatIdentity identity,
            String callId,
            String mode,
            String system,
            String user,
            boolean fallback,
            Long tokenBudget,
            Consumer<GenerationEvent> events) {
        return generate(
                identity, callId, mode, system, user, List.of(), null, fallback, tokenBudget, events);
    }

    public Completion completeVision(
            ChatIdentity identity,
            String callId,
            String system,
            String user,
            String imageUrl,
            Consumer<GenerationEvent> events) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("图片地址不能为空");
        }

        return generate(
                identity,
                callId,
                "VISION",
                system,
                user,
                List.of(new VisionImage(null, imageUrl)),
                imageUrl,
                true,
                null,
                events);
    }

    public Completion completeVision(
            ChatIdentity identity,
            String callId,
            String system,
            String user,
            List<VisionImage> images,
            Long tokenBudget,
            Consumer<GenerationEvent> events) {
        if (images == null || images.isEmpty() || images.size() > 3) {
            throw new IllegalArgumentException("图片数量必须为 1 至 3 张");
        }

        var ids = new HashSet<String>();

        for (VisionImage image : images) {
            if (image == null
                    || image.assetId() == null
                    || !image.assetId().matches("[A-Za-z0-9_.:@-]{1,128}")
                    || !ids.add(image.assetId())
                    || image.url() == null
                    || image.url().isBlank()) {
                throw new IllegalArgumentException("图片资产引用无效");
            }
        }

        return generate(
                identity, callId, "VISION", system, user, List.copyOf(images), null, true,
                tokenBudget, events);
    }

    // 2. 主备生成：先取得调用组准入，再执行主模型及满足条件的备用模型。
    private Completion generate(
            ChatIdentity identity,
            String callId,
            String mode,
            String system,
            String user,
            List<VisionImage> images,
            String legacyImageUrl,
            boolean fallback,
            Long tokenBudget,
            Consumer<GenerationEvent> events) {
        Objects.requireNonNull(events, "generation events");

        if (!Set.of("CHAT", "VISION", "DOCUMENT_QA", "SUMMARY", "EVALUATION", "DECISION", "CLASSIFY")
                .contains(mode)) {
            throw new IllegalArgumentException("模型调用模式无效");
        }

        if ("VISION".equals(mode) && images.isEmpty()) {
            throw new IllegalArgumentException("图片地址不能为空");
        }

        requireText(user, 128000);

        if (system == null) {
            system = "";
        }

        requireText(system, 32000, true);
        String capability =
                "VISION".equals(mode)
                        ? "VISION"
                        : "EVALUATION".equals(mode) ? "EVALUATION" : "CHAT";
        GroupState group =
                admit(
                        identity,
                        callId,
                        hash(
                                images.isEmpty()
                                        ? List.of(mode, system, user, fallback)
                                        : List.of(
                                                mode,
                                                system,
                                                user,
                                                legacyImageUrl == null
                                                        ? images.stream().map(VisionImage::assetId).toList()
                                                        : legacyImageUrl,
                                                fallback)),
                        capability,
                        !"SUMMARY".equals(mode));

        // 2.1. 回执优先：已有结果时重放并提交，不再调用供应商。
        if (group.receipt() != null) {
            Completion result = replay(identity, callId, group, Completion.class);

            committed(events, result);

            return result;
        }

        requireUndispatched(group);
        // 2.2. Token 上界：先为主调用检查预算，再判断是否还能容纳备用调用。
        long budget = tokenBudget == null ? 64000 : Math.min(64000L, tokenBudget);
        long primaryBound = TokenBudgetEstimator.generation(mode, system, user, false, images.size()),
                fallbackBound = TokenBudgetEstimator.generation(mode, system, user, true, images.size());

        if (budget < primaryBound) {
            try {
                billing.cancelGroup(identity, callId, group.generation())
                        .block(Duration.ofSeconds(6));
            } catch (Exception ignored) {
            }

            throw new ModelCallException("MODEL_TOKEN_BUDGET", false, 429);
        }

        var selected = requireModel(identity,callId,group,ModelCapability.parse(capability));
        // 2.2.1. 备用限制：评估与决策不静默更换模型，其他模式仍受预算和调用条件限制。
        boolean allow =
                fallback
                        && !"EVALUATION".equals(mode)
                        && !"DECISION".equals(mode)
                        && !"CLASSIFY".equals(mode)
                        && budget - primaryBound >= fallbackBound;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        var primaryOutput = new AtomicBoolean();

        try {
            if (circuit != null && !circuit.acquire(selected)) {
                billing.attempt(
                                identity,
                                callId,
                                new AttemptReserve(
                                        "PRIMARY",
                                        selected.provider().name(),
                                        selected.model(),
                                        endpoint(selected),
                                        selected.credentialId(),
                                        primaryBound))
                        .block(Duration.ofSeconds(6));
                billing.skipAttempt(identity, callId, "PRIMARY", group.generation())
                        .block(Duration.ofSeconds(6));

                throw new ModelCallException("CIRCUIT_OPEN", true, 0);
            }

            Completion result =
                    completeAttempt(
                            identity,
                            callId,
                            mode,
                            system,
                            user,
                            images,
                            selected,
                            false,
                            "CLASSIFY".equals(mode) && total.compareTo(Duration.ofSeconds(5)) > 0
                                    ? Duration.ofSeconds(5) : total,
                            events,
                            primaryOutput,
                            false,
                            group.generation(),
                            group.unknownTokens());

            if (circuit != null) {
                circuit.success(selected);
            }

            return result;
        } catch (ModelCallException failure) {
            if (circuit != null && failure.retryable && !"CIRCUIT_OPEN".equals(failure.code())) {
                circuit.failure(selected);
            }

            if (!allow || !failure.retryable || Thread.currentThread().isInterrupted()) {
                {
                    try {
                        billing.cancelGroup(identity, callId, group.generation())
                                .block(Duration.ofSeconds(6));
                    } catch (Exception ignored) {
                    }
                }

                throw failure;
            }

            var backup =
                    requireModel(
                            identity,callId,group,
                            "VISION".equals(mode)
                                    ? ModelCapability.VISION_FALLBACK
                                    : ModelCapability.FALLBACK);

            if (failure.status == 401
                    && Objects.equals(selected.credentialId(), backup.credentialId())) {
                throw new ModelCallException("PROVIDER_AUTHENTICATION", false, 401);
            }

            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));

            if (remaining.isZero()) {
                throw new ModelCallException("TIMEOUT", false, 0);
            }

            String reason =
                    failure.code.equals("EMPTY_OUTPUT")
                            ? "empty"
                            : failure.code.equals("CIRCUIT_OPEN")
                                    ? "circuit"
                                    : failure.status > 0 ? "provider" : "timeout";

            try {
                Completion answer =
                        completeAttempt(
                                identity,
                                callId,
                                mode,
                                system,
                                user,
                                images,
                                backup,
                                true,
                                remaining.compareTo(backupTotal) < 0 ? remaining : backupTotal,
                                events,
                                primaryOutput,
                                primaryOutput.get(),
                                group.generation(),
                                Math.addExact(
                                        group.unknownTokens(),
                                        Set.of("CIRCUIT_OPEN","PROVIDER_REJECTED","PROVIDER_CONNECT_FAILED").contains(failure.code) ? 0 : primaryBound));

                metrics.counter("ai.model.fallback", "reason", reason, "outcome", "success")
                        .increment();

                return answer;
            } catch (RuntimeException error) {
                metrics.counter("ai.model.fallback", "reason", reason, "outcome", "failure")
                        .increment();
                throw error;
            }
        }
    }

    // 3. 向量调用：使用独立能力和输入预算，结果归入可恢复调用组。
    public EmbeddingBatch embed(
            ChatIdentity identity, String callId, List<String> texts, int dimensions) {
        return embed(identity, callId, texts, dimensions, null);
    }

    public EmbeddingBatch embed(
            ChatIdentity identity,
            String callId,
            List<String> texts,
            int dimensions,
            Long tokenBudget) {
        if (texts == null
                || texts.isEmpty()
                || texts.size() > 10
                || !Set.of(64, 128, 256, 512, 768, 1024, 1536, 2048).contains(dimensions)) {
            throw new IllegalArgumentException("向量批次或维度无效");
        }

        texts.forEach(t -> requireText(t, 8192));
        var g =
                admit(
                        identity,
                        callId,
                        hash(List.of("EMBEDDING", texts, dimensions)),
                        "EMBEDDING",
                        false);

        if (g.receipt() != null) {
            return replay(identity, callId, g, EmbeddingBatch.class);
        }

        requireUndispatched(g);
        long upper = TokenBudgetEstimator.embedding(texts);

        requireTokenBudget(identity, callId, g, tokenBudget, upper);
        var model = requireModel(identity,callId,g,ModelCapability.EMBEDDING);
        boolean dispatched = false, succeeded = false, providerSucceeded=false;
        long started = System.nanoTime();

        try {
            if(circuit!=null&&!circuit.acquire(model))throw new ModelCallException("CIRCUIT_OPEN",false,503);
            begin(identity, callId, model, "PRIMARY", g.generation(), upper);
            dispatched = true;
            JsonNode result =
                    transport.postJson(
                            model,
                            "/embeddings",
                            Map.of(
                                    "model",
                                    model.model(),
                                    "input",
                                    texts,
                                    "dimensions",
                                    dimensions,
                                    "encoding_format",
                                    "float"),
                            total);
            long input = token(result, "prompt_tokens");
            JsonNode data = result.path("data");

            if (!data.isArray() || data.size() != texts.size()) {
                throw protocol("EMBEDDING_CARDINALITY");
            }

            List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));

            for (JsonNode item : data) {
                if (!item.path("index").isIntegralNumber()) {
                    throw protocol("EMBEDDING_INDEX");
                }

                int index = item.path("index").asInt(-1);
                JsonNode vector = item.path("embedding");

                if (index < 0
                        || index >= texts.size()
                        || vectors.get(index) != null
                        || !vector.isArray()
                        || vector.size() != dimensions) {
                    throw protocol("EMBEDDING_DIMENSION");
                }

                List<Float> values = new ArrayList<>();

                for (JsonNode coordinate : vector) {
                    float value = coordinate.floatValue();

                    if (!coordinate.isNumber() || !Float.isFinite(value)) {
                        throw protocol("EMBEDDING_VALUE");
                    }

                    values.add(value);
                }

                vectors.set(index, List.copyOf(values));
            }

            var batch =
                    new EmbeddingBatch(
                            List.copyOf(vectors),
                            input,
                            ModelFingerprint.embedding(
                                    model.provider().name(),
                                    model.model(),
                                    model.baseUrl(),
                                    model.credentialId(),
                                    dimensions));

            providerSucceeded=true;
            if(circuit!=null)circuit.success(model);
            save(
                    identity,
                    callId,
                    "PRIMARY",
                    batch,
                    input,
                    0,
                    result.path("id").asString(null),
                    g.generation());
            recordTokens(ModelCapability.EMBEDDING, input, 0);
            succeeded = true;

            return batch;
        } catch (Exception error) {
            if(dispatched&&!providerSucceeded&&circuit!=null&&circuitFailure(error))circuit.failure(model);
            throw unknown(identity, callId, "PRIMARY", error, g.generation(), dispatched);
        } finally {
            recordDuration(ModelCapability.EMBEDDING, "PRIMARY", succeeded, started);
        }
    }

    // 4. 重排序调用：按查询与候选文本估计预算，再解析排序结果。
    public Ranking rerank(
            ChatIdentity identity, String callId, String query, List<String> documents) {
        return rerank(identity, callId, query, documents, null);
    }

    public Ranking rerank(
            ChatIdentity identity,
            String callId,
            String query,
            List<String> documents,
            Long tokenBudget) {
        requireText(query, 16000);

        if (documents == null || documents.isEmpty() || documents.size() > 500) {
            throw new IllegalArgumentException("重排候选无效");
        }

        documents.forEach(d -> requireText(d, 16000));
        var g = admit(identity, callId, hash(List.of("RERANK", query, documents)), "RERANK", true);

        if (g.receipt() != null) {
            return replay(identity, callId, g, Ranking.class);
        }

        requireUndispatched(g);
        long upper = TokenBudgetEstimator.rerank(query, documents);

        requireTokenBudget(identity, callId, g, tokenBudget, upper);
        var model = requireModel(identity,callId,g,ModelCapability.RERANK);
        boolean dispatched = false, succeeded = false, providerSucceeded=false;
        long started = System.nanoTime();

        try {
            if(circuit!=null&&!circuit.acquire(model))throw new ModelCallException("CIRCUIT_OPEN",false,503);
            begin(identity, callId, model, "PRIMARY", g.generation(), upper);
            dispatched = true;
            JsonNode result =
                    transport.postJson(
                            model,
                            "/reranks",
                            Map.of(
                                    "model",
                                    model.model(),
                                    "query",
                                    query,
                                    "documents",
                                    documents,
                                    "top_n",
                                    documents.size()),
                            total);
            long input = token(result, "total_tokens");
            JsonNode results = result.path("results");

            if (!results.isArray() || results.isEmpty()) {
                throw protocol("RERANK_EMPTY");
            }

            List<Ranked> ranked = new ArrayList<>();
            Set<Integer> indices = new HashSet<>();

            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);

                if (!item.path("index").isIntegralNumber()
                        || index < 0
                        || index >= documents.size()
                        || !indices.add(index)
                        || !Double.isFinite(score)
                        || score < 0
                        || score > 1) {
                    throw protocol("RERANK_INDEX_OR_SCORE");
                }

                ranked.add(new Ranked(index, score));
            }

            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
            var ranking = new Ranking(List.copyOf(ranked), input);

            providerSucceeded=true;
            if(circuit!=null)circuit.success(model);
            save(
                    identity,
                    callId,
                    "PRIMARY",
                    ranking,
                    input,
                    0,
                    result.path("id").asString(null),
                    g.generation());
            recordTokens(ModelCapability.RERANK, input, 0);
            succeeded = true;

            return ranking;
        } catch (Exception error) {
            if(dispatched&&!providerSucceeded&&circuit!=null&&circuitFailure(error))circuit.failure(model);
            throw unknown(identity, callId, "PRIMARY", error, g.generation(), dispatched);
        } finally {
            recordDuration(ModelCapability.RERANK, "PRIMARY", succeeded, started);
        }
    }

    // 5. 单次生成尝试：登记供应商快照与派发状态，区分流式输出和最终回执。
    private Completion completeAttempt(
            ChatIdentity i,
            String id,
            String mode,
            String system,
            String user,
            List<VisionImage> images,
            AuthModelClient.SelectedModel model,
            boolean backup,
            Duration timeout,
            Consumer<GenerationEvent> events,
            AtomicBoolean primaryOutput,
            boolean replace,
            int generation,
            long unknownTokens) {
        String role = backup ? "FALLBACK" : "PRIMARY";
        long started = System.nanoTime();
        boolean succeeded = false, dispatched = false;

        try {
            begin(
                    i,
                    id,
                    model,
                    role,
                    generation,
                    TokenBudgetEstimator.generation(mode, system, user, backup, images.size()));
            dispatched = true;

            if (backup) {
                events.accept(
                        new GenerationEvent(
                                "fallback_started",
                                2,
                                statusPayload(model, mode, true, "RUNNING")));
            }

            events.accept(
                    new GenerationEvent(
                            "model_status",
                            backup ? 2 : 1,
                            statusPayload(model, mode, backup, "RUNNING")));
            var result = transport.stream(model, mode, system, user, images, backup,
                    timeout, events, primaryOutput, replace, unknownTokens);
            var completed = result.completion();
            long input = completed.inputTokens(), tokens = completed.outputTokens();

            save(i, id, role, completed, input, tokens, result.providerId(), generation);
            recordTokens(model.capability(), input, tokens);
            succeeded = true;
            events.accept(
                    new GenerationEvent(
                            "model_status",
                            backup ? 2 : 1,
                            statusPayload(model, mode, backup, "SUCCEEDED")));
            committed(events, completed);

            return completed;
        } catch (Exception error) {
            var failure = unknown(i, id, role, error, generation, dispatched, !backup);

            if (dispatched && !Thread.currentThread().isInterrupted()) {
                events.accept(
                        new GenerationEvent(
                                "model_status",
                                backup ? 2 : 1,
                                statusPayload(model, mode, backup, "FAILED")));
            }

            throw failure;
        } finally {
            recordDuration(model.capability(), role, succeeded, started);
        }
    }

    // 5.1. 调用组准入：等待钱包和平台容量，并保留取消与超时语义。
    private GroupState admit(
            ChatIdentity i, String id, String hash, String capability, boolean foreground) {
        if (i == null
                || !i.authenticated()
                || id == null
                || !id.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("模型调用身份或 ID 无效");
        }

        long until = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        Integer expectedGeneration = null;

        for (; ; ) {
            checkCancelled();
            GroupState g;

            try {
                g = billing.reserveGroup(
                                i, new GroupReserve(id, hash, capability, foreground, expectedGeneration))
                        .block(Duration.ofSeconds(6));
            } catch (org.springframework.web.server.ResponseStatusException error) {
                if (error.getStatusCode().value() == 409) {
                    throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
                }

                throw error;
            }

            if (g == null) {
                throw protocol("BILLING_UNAVAILABLE");
            }
            if("ABANDONED".equals(g.status()))throw new ModelCallException("MODEL_CALL_INTERRUPTED",false,400);

            if (expectedGeneration != null && g.generation() != expectedGeneration) {
                throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
            }

            expectedGeneration = g.generation();

            if (!"QUEUED".equals(g.status())) {
                return g;
            }

            if (System.nanoTime() >= until) {
                throw new ModelCallException("ADMISSION_TIMEOUT", false, 0);
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelCallException("CANCELLED", false, 0);
            }
        }
    }

    // 5.2. 尝试登记：将角色、模型、端点和计划 Token 上界绑定到当前代次。
    private void begin(
            ChatIdentity i,
            String id,
            AuthModelClient.SelectedModel model,
            String role,
            int generation,
            long upper) {
        checkCancelled();
        var a =
                billing.attempt(
                                i,
                                id,
                                new AttemptReserve(
                                        role,
                                        model.provider().name(),
                                        model.model(),
                                        endpoint(model),
                                        model.credentialId() == null
                                                ? "platform"
                                                : model.credentialId(),
                                        upper))
                        .block(Duration.ofSeconds(6));

        if (a == null || !"RESERVED".equals(a.status()) || a.generation() != generation) {
            throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
        }

        billing.dispatchAttempt(i, id, role, generation).block(Duration.ofSeconds(6));
        checkCancelled();
    }

    // 5.3. 结果保存：先持久化回执，再完成调用组结算。
    private void save(
            ChatIdentity i,
            String id,
            String role,
            Object result,
            long input,
            long output,
            String providerId,
            int generation) {
        billing.receipt(
                        i,
                        id,
                        new AttemptReceipt(
                                role,
                                json.writeValueAsString(result),
                                input,
                                output,
                                providerId,
                                generation))
                .block(Duration.ofSeconds(6));
        billing.finish(i, id).block(Duration.ofSeconds(6));
    }

    // 6. 结果恢复：按原调用组回执解析结果，避免幂等重试再次付费。
    private <T> T replay(ChatIdentity i, String id, GroupState g, Class<T> type) {
        if (!"SUCCEEDED".equals(g.status())) {
            billing.finish(i, id).block(Duration.ofSeconds(6));
        }

        return type == Completion.class
                ? type.cast(completionFromReceipt(g.receipt()))
                : json.readValue(g.receipt(), type);
    }

    /** Cached receipts from the original five-field protocol retain their actual accounting. */
    public static Completion completionFromReceipt(String receipt) {
        JsonNode node = JsonMapper.builder().build().readTree(receipt);
        long input = node.path("inputTokens").asLong(), output = node.path("outputTokens").asLong();

        return new Completion(
                node.path("text").asString(),
                input,
                output,
                node.path("model").asString(),
                node.path("fallback").asBoolean(),
                node.path("reservedTokens").asLong(Math.addExact(input, output)),
                node.path("unknownTokens").asLong(0),
                node.path("provider").asString(null));
    }

    // 6.1. 重发保护：已派发但结果不明的请求必须先核对，不能直接发起新尝试。
    private static void requireUndispatched(GroupState g) {
        if (!"RESERVED".equals(g.status())) {
            throw new ModelCallException("MODEL_OUTCOME_UNKNOWN", false, 409);
        }
    }

    private AuthModelClient.SelectedModel requireModel(ChatIdentity i,String call,GroupState group,ModelCapability c) {
        try {return requireModel(i,c);} catch(RuntimeException error) {
            try {billing.cancelGroup(i,call,group.generation()).block(Duration.ofSeconds(6));}catch(Exception ignored){}
            throw error;
        }
    }

    private AuthModelClient.SelectedModel requireModel(ChatIdentity i, ModelCapability c) {
        var m = models.resolve(i, c);

        if (m == null || m.apiKey() == null || m.apiKey().isBlank()) {
            throw new ModelCallException("MODEL_NOT_CONFIGURED", false, 503);
        }

        return m;
    }

    // 7. 不明结果：保留尝试的 UNKNOWN 状态，交由后续核对确认真实消耗。
    private ModelCallException unknown(
            ChatIdentity i,
            String id,
            String role,
            Exception error,
            int generation,
            boolean dispatched) {
        return unknown(i,id,role,error,generation,dispatched,false);
    }

    private ModelCallException unknown(ChatIdentity i,String id,String role,Exception error,int generation,boolean dispatched,boolean retainForFallback) {
        boolean interrupted = Thread.interrupted();
        String evidence=definiteFailure(error);
        boolean rejected=false;
        try {
            if(dispatched && evidence!=null) {
                var state=billing.rejectAttempt(i,id,role,generation,evidence,retainForFallback).block(Duration.ofSeconds(6));
                rejected=state!=null&&!"UNKNOWN".equals(state.status());
            } else if (dispatched) {
                billing.unknownAttempt(i, id, role, generation).block(Duration.ofSeconds(6));
            } else {
                billing.cancelGroup(i, id, generation).block(Duration.ofSeconds(6));
            }
        } catch (Exception ignored) {
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (dispatched && evidence==null) {
            metrics.counter("ai.model.unknown", "role", role).increment();
        }
        if(dispatched && evidence!=null && !rejected)return new ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);
        if(rejected) return new ModelCallException(evidence.equals("CONNECT_FAILED")?"PROVIDER_CONNECT_FAILED":"PROVIDER_REJECTED",error instanceof ModelCallException m?m.retryable:true,error instanceof ModelCallException m?m.status():503);

        if (interrupted || error instanceof java.util.concurrent.CancellationException) {
            return new ModelCallException("CANCELLED", false, 0);
        }

        if (error instanceof ModelCallException e) {
            return e;
        }

        if (error instanceof org.springframework.web.server.ResponseStatusException response) {
            return new ModelCallException(
                    response.getStatusCode().value() == 402
                            ? "MODEL_PAYMENT_BUDGET"
                            : "BILLING_RECONCILIATION",
                    false,
                    response.getStatusCode().value() == 402 ? 402 : 409);
        }

        return new ModelCallException(
                "PROVIDER_TIMEOUT_OR_CONNECTION", dispatched, dispatched ? 0 : 409);
    }

    private static String definiteFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof DefiniteProviderFailure failure)return "HTTP_"+failure.status();
            if(cause instanceof ProviderConnectFailure)return "CONNECT_FAILED";
        }
        return null;
    }
    private static boolean circuitFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof DefiniteProviderFailure rejected)return rejected.status()==429||rejected.status()>=500;
        }
        return true;
    }

    private void recordDuration(
            ModelCapability capability, String role, boolean succeeded, long started) {
        metrics.timer(
                        "ai.model.duration",
                        "capability",
                        capability == ModelCapability.FALLBACK ? "CHAT" : capability.name(),
                        "role",
                        role,
                        "outcome",
                        succeeded ? "success" : "failure")
                .record(Duration.ofNanos(System.nanoTime() - started));
    }

    private void recordTokens(ModelCapability capability, long input, long output) {
        String name = capability == ModelCapability.FALLBACK ? "CHAT" : capability.name();

        metrics.counter("ai.model.tokens", "direction", "input", "capability", name)
                .increment(input);
        metrics.counter("ai.model.tokens", "direction", "output", "capability", name)
                .increment(output);
    }

    private void requireTokenBudget(
            ChatIdentity i, String id, GroupState g, Long budget, long bound) {
        if (budget != null && budget < bound) {
            try {
                billing.cancelGroup(i, id, g.generation()).block(Duration.ofSeconds(6));
            } catch (Exception ignored) {
            }

            throw new ModelCallException("MODEL_TOKEN_BUDGET", false, 429);
        }
    }

    public static GenerationEvent committedEvent(Completion result) {
        var payload =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "text",
                                result.text(),
                                "inputTokens",
                                result.inputTokens(),
                                "outputTokens",
                                result.outputTokens(),
                                "model",
                                result.model(),
                                "fallback",
                                result.fallback(),
                                "reservedTokens",
                                result.reservedTokens(),
                                "unknownTokens",
                                result.unknownTokens()));

        if (result.provider() != null) {
            payload.put("provider", result.provider());
        }

        return new GenerationEvent("answer_committed", result.fallback() ? 2 : 1, payload);
    }

    private static Map<String, Object> statusPayload(
            AuthModelClient.SelectedModel model, String mode, boolean backup, String state) {
        return Map.of(
                "provider",
                model.provider().name(),
                "model",
                model.model(),
                "capability",
                mode,
                "state",
                state,
                "fallback",
                backup,
                "role",
                backup ? "FALLBACK" : "PRIMARY",
                "message",
                (model.provider() == ModelProvider.GPT
                                ? "ChatGPT"
                                : model.provider() == ModelProvider.QWEN
                                        ? "千问"
                                        : model.provider().name())
                        + (backup ? "兜底" : "")
                        + switch (state) {
                            case "RUNNING" -> "处理中";
                            case "SUCCEEDED" -> "已完成";
                            default -> "调用失败";
                        });
    }

    private static void committed(Consumer<GenerationEvent> events, Completion result) {
        events.accept(committedEvent(result));
    }

    private String hash(Object params) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(params)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireText(String value, int max) {
        requireText(value, max, false);
    }

    private static void requireText(String value, int max, boolean emptyAllowed) {
        if (value == null
                || !emptyAllowed && value.isBlank()
                || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException("模型输入为空或超出上限");
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ModelCallException("CANCELLED", false, 0);
        }
    }

    /** 统一模型调用异常，携带错误码、HTTP 状态及重试判断信息。 */
    public static class ModelCallException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final int status;

        public ModelCallException(String code, boolean retryable, int status) {
            super("当前回答服务暂时不可用，你的问题和处理进度已保留。可以稍后重试。");
            this.code = code;
            this.retryable = retryable;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }
}
