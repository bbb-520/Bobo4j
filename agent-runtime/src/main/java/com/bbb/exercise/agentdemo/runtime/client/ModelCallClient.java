package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.*;
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
    private final AuthModelClient models;private final BillingClient billing;private final WebClient http;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Duration first,idle,total,backupTotal;private final ModelCircuit circuit;
    private MeterRegistry metrics=new SimpleMeterRegistry();
    public ModelCallClient(AuthModelClient models,BillingClient billing,WebClient.Builder http) {
        this(models,billing,http,null,15,20,60,30);
    }
    public ModelCallClient(AuthModelClient models,BillingClient billing,WebClient.Builder http,ModelCircuit circuit,
            int first,int idle,int total,int backupTotal) {
        this.models=models;this.billing=billing;this.http=http.clone().codecs(c -> c.defaultCodecs().maxInMemorySize(16_000_000)).build();this.circuit=circuit;
        if(first<=0||idle<=0||total<=0||backupTotal<=0)throw new IllegalArgumentException("模型超时配置无效");
        this.first=Duration.ofSeconds(first);this.idle=Duration.ofSeconds(idle);this.total=Duration.ofSeconds(Math.min(total,60));this.backupTotal=Duration.ofSeconds(Math.min(backupTotal,30));
    }
    @Autowired
    public ModelCallClient(AuthModelClient models,BillingClient billing,WebClient.Builder http,ModelCircuit circuit,
            @Value("${app.model.first-output-timeout-seconds:${MODEL_FIRST_OUTPUT_TIMEOUT_SECONDS:15}}") int first,
            @Value("${app.model.idle-timeout-seconds:${MODEL_IDLE_TIMEOUT_SECONDS:20}}") int idle,
            @Value("${app.model.total-timeout-seconds:${MODEL_TOTAL_TIMEOUT_SECONDS:60}}") int total,
            @Value("${app.model.fallback-timeout-seconds:${MODEL_FALLBACK_TIMEOUT_SECONDS:30}}") int backupTotal,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> registry) {
        this(models,billing,http,circuit,first,idle,total,backupTotal);this.metrics=registry.getIfAvailable(SimpleMeterRegistry::new);
        io.micrometer.core.instrument.Gauge.builder("ai.model.circuit.open",circuit,c -> c.openCount()).register(metrics);
    }
    /** fallback means allowFallback; EVALUATION and DECISION never silently change their judge/schema. */
    public Completion complete(ChatIdentity identity,String callId,String mode,String system,String user,boolean fallback) {
        return completeStreaming(identity,callId,mode,system,user,fallback,event -> {});
    }
    public Completion complete(ChatIdentity identity,String callId,String mode,String system,String user,boolean fallback,Long tokenBudget) {return completeStreaming(identity,callId,mode,system,user,fallback,tokenBudget,event -> {});}
    public Completion completeStreaming(ChatIdentity identity,String callId,String mode,String system,String user,boolean fallback,Consumer<GenerationEvent> events) {
        return completeStreaming(identity,callId,mode,system,user,fallback,null,events);
    }
    public Completion completeStreaming(ChatIdentity identity,String callId,String mode,String system,String user,boolean fallback,Long tokenBudget,Consumer<GenerationEvent> events) {
        Objects.requireNonNull(events,"generation events");
        if(!Set.of("CHAT","DOCUMENT_QA","SUMMARY","EVALUATION","DECISION").contains(mode))throw new IllegalArgumentException("模型调用模式无效");
        requireText(user,128000);if(system==null)system="";requireText(system,32000,true);
        String capability="EVALUATION".equals(mode)?"EVALUATION":"CHAT";
        GroupState group=admit(identity,callId,hash(List.of(mode,system,user,fallback)),capability,!"SUMMARY".equals(mode));
        if(group.receipt()!=null){Completion result=replay(identity,callId,group,Completion.class);committed(events,result);return result;}
        requireUndispatched(group);
        long budget=tokenBudget==null?64000:tokenBudget;
        long primaryBound=TokenBudgetEstimator.completion(mode,system,user,false),fallbackBound=TokenBudgetEstimator.completion(mode,system,user,true);
        if(budget<primaryBound){try{billing.cancelGroup(identity,callId,group.generation()).block(Duration.ofSeconds(6));}catch(Exception ignored){}throw new ModelCallException("MODEL_TOKEN_BUDGET",false,429);}
        var selected=requireModel(identity,ModelCapability.parse(capability));
        boolean allow=fallback&&!"EVALUATION".equals(mode)&&!"DECISION".equals(mode)&&budget-primaryBound>=fallbackBound;
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        var primaryOutput=new AtomicBoolean();
        try {
            if(circuit!=null&&!circuit.acquire(selected)){billing.attempt(identity,callId,new AttemptReserve("PRIMARY",selected.provider().name(),selected.model(),endpoint(selected),selected.credentialId(),primaryBound)).block(Duration.ofSeconds(6));billing.skipAttempt(identity,callId,"PRIMARY",group.generation()).block(Duration.ofSeconds(6));throw new ModelCallException("CIRCUIT_OPEN",true,0);}
            Completion result=completeAttempt(identity,callId,mode,system,user,selected,false,total,events,primaryOutput,false,group.generation(),group.unknownTokens());
            if(circuit!=null)circuit.success(selected);return result;
        } catch(ModelCallException failure) {
            if(circuit!=null&&failure.retryable)circuit.failure(selected);
            if(!allow||!failure.retryable||Thread.currentThread().isInterrupted()){if(failure.code.equals("CIRCUIT_OPEN"))try{billing.cancelGroup(identity,callId,group.generation()).block(Duration.ofSeconds(6));}catch(Exception ignored){}throw failure;}
            var backup=requireModel(identity,ModelCapability.FALLBACK);
            if(failure.status==401&&Objects.equals(selected.credentialId(),backup.credentialId()))throw new ModelCallException("PROVIDER_AUTHENTICATION",false,401);
            Duration remaining=Duration.ofNanos(Math.max(0,deadline-System.nanoTime()));
            if(remaining.isZero())throw new ModelCallException("TIMEOUT",false,0);
            String reason=failure.code.equals("EMPTY_OUTPUT")?"empty":failure.code.equals("CIRCUIT_OPEN")?"circuit":failure.status>0?"provider":"timeout";
            try {Completion answer=completeAttempt(identity,callId,mode,system,user,backup,true,remaining.compareTo(backupTotal)<0?remaining:backupTotal,events,primaryOutput,primaryOutput.get(),group.generation(),Math.addExact(group.unknownTokens(),failure.code.equals("CIRCUIT_OPEN")?0:primaryBound));metrics.counter("ai.model.fallback","reason",reason,"outcome","success").increment();return answer;}
            catch(RuntimeException error){metrics.counter("ai.model.fallback","reason",reason,"outcome","failure").increment();throw error;}
        }
    }
    public EmbeddingBatch embed(ChatIdentity identity,String callId,List<String> texts,int dimensions) {
        return embed(identity,callId,texts,dimensions,null);
    }
    public EmbeddingBatch embed(ChatIdentity identity,String callId,List<String> texts,int dimensions,Long tokenBudget) {
        if(texts==null||texts.isEmpty()||texts.size()>10||!Set.of(64,128,256,512,768,1024,1536,2048).contains(dimensions))throw new IllegalArgumentException("向量批次或维度无效");
        texts.forEach(t -> requireText(t,8192));
        var g=admit(identity,callId,hash(List.of("EMBEDDING",texts,dimensions)),"EMBEDDING",false);
        if(g.receipt()!=null)return replay(identity,callId,g,EmbeddingBatch.class);requireUndispatched(g);
        long upper=TokenBudgetEstimator.embedding(texts);requireTokenBudget(identity,callId,g,tokenBudget,upper);
        var model=requireModel(identity,ModelCapability.EMBEDDING);
        boolean dispatched=false,succeeded=false;long started=System.nanoTime();
        try {
            begin(identity,callId,model,"PRIMARY",g.generation(),upper);
            dispatched=true;
            JsonNode result=postJson(model,"/embeddings",Map.of("model",model.model(),"input",texts,"dimensions",dimensions,"encoding_format","float"),total);
            long input=token(result,"prompt_tokens");JsonNode data=result.path("data");
            if(!data.isArray()||data.size()!=texts.size())throw protocol("EMBEDDING_CARDINALITY");
            List<List<Float>> vectors=new ArrayList<>(Collections.nCopies(texts.size(),null));
            for(JsonNode item:data) {
                if(!item.path("index").isIntegralNumber())throw protocol("EMBEDDING_INDEX");int index=item.path("index").asInt(-1);
                JsonNode vector=item.path("embedding");if(index<0||index>=texts.size()||vectors.get(index)!=null||!vector.isArray()||vector.size()!=dimensions)throw protocol("EMBEDDING_DIMENSION");
                List<Float> values=new ArrayList<>();for(JsonNode coordinate:vector){float value=coordinate.floatValue();if(!coordinate.isNumber()||!Float.isFinite(value))throw protocol("EMBEDDING_VALUE");values.add(value);}vectors.set(index,List.copyOf(values));
            }
            var batch=new EmbeddingBatch(List.copyOf(vectors),input,ModelFingerprint.embedding(model.provider().name(),model.model(),model.baseUrl(),model.credentialId(),dimensions));save(identity,callId,"PRIMARY",batch,input,0,result.path("id").asString(null),g.generation());recordTokens(ModelCapability.EMBEDDING,input,0);succeeded=true;return batch;
        } catch(Exception error){throw unknown(identity,callId,"PRIMARY",error,g.generation(),dispatched);}
        finally {recordDuration(ModelCapability.EMBEDDING,"PRIMARY",succeeded,started);}
    }
    public Ranking rerank(ChatIdentity identity,String callId,String query,List<String> documents) {
        return rerank(identity,callId,query,documents,null);
    }
    public Ranking rerank(ChatIdentity identity,String callId,String query,List<String> documents,Long tokenBudget) {
        requireText(query,16000);if(documents==null||documents.isEmpty()||documents.size()>500)throw new IllegalArgumentException("重排候选无效");documents.forEach(d -> requireText(d,16000));
        var g=admit(identity,callId,hash(List.of("RERANK",query,documents)),"RERANK",true);
        if(g.receipt()!=null)return replay(identity,callId,g,Ranking.class);requireUndispatched(g);
        long upper=TokenBudgetEstimator.rerank(query,documents);requireTokenBudget(identity,callId,g,tokenBudget,upper);
        var model=requireModel(identity,ModelCapability.RERANK);
        boolean dispatched=false,succeeded=false;long started=System.nanoTime();
        try {
            begin(identity,callId,model,"PRIMARY",g.generation(),upper);
            dispatched=true;
            JsonNode result=postJson(model,"/reranks",Map.of("model",model.model(),"query",query,"documents",documents,"top_n",documents.size()),total);
            long input=token(result,"total_tokens");JsonNode results=result.path("results");
            if(!results.isArray()||results.isEmpty())throw protocol("RERANK_EMPTY");
            List<Ranked> ranked=new ArrayList<>();Set<Integer> indices=new HashSet<>();
            for(JsonNode item:results){int index=item.path("index").asInt(-1);double score=item.path("relevance_score").asDouble(Double.NaN);if(!item.path("index").isIntegralNumber()||index<0||index>=documents.size()||!indices.add(index)||!Double.isFinite(score)||score<0||score>1)throw protocol("RERANK_INDEX_OR_SCORE");ranked.add(new Ranked(index,score));}
            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());var ranking=new Ranking(List.copyOf(ranked),input);save(identity,callId,"PRIMARY",ranking,input,0,result.path("id").asString(null),g.generation());recordTokens(ModelCapability.RERANK,input,0);succeeded=true;return ranking;
        }catch(Exception error){throw unknown(identity,callId,"PRIMARY",error,g.generation(),dispatched);}
        finally {recordDuration(ModelCapability.RERANK,"PRIMARY",succeeded,started);}
    }
    private Completion completeAttempt(ChatIdentity i,String id,String mode,String system,String user,AuthModelClient.SelectedModel model,boolean backup,Duration timeout,Consumer<GenerationEvent> events,AtomicBoolean primaryOutput,boolean replace,int generation,long unknownTokens) {
        String role=backup?"FALLBACK":"PRIMARY";long started=System.nanoTime();boolean succeeded=false,dispatched=false;
        try {
            begin(i,id,model,role,generation,TokenBudgetEstimator.completion(mode,system,user,backup));
            dispatched=true;
            if(backup)events.accept(new GenerationEvent("fallback_started",2,Map.of("message","当前服务响应较慢，正在切换备用服务")));
            String trusted=ModelPrompts.forMode(mode,backup)+"\n"+system;
            Map<String,Object> request=new LinkedHashMap<>();request.put("model",model.model());request.put("messages",List.of(Map.of("role","system","content",trusted),Map.of("role","user","content",user)));request.put("stream",true);request.put("stream_options",Map.of("include_usage",true));request.put("max_tokens",2048);request.put("temperature",backup?0.2:0.3);
            if(model.provider()==ModelProvider.QWEN)request.put("enable_thinking",false);
            if("DECISION".equals(mode))request.put("response_format",Map.of("type","json_object"));
            var usage=new AtomicReference<JsonNode>();var providerId=new AtomicReference<String>();
            var firstBackup=new AtomicBoolean(true);
            Flux<JsonNode> frames=http.post().uri(endpoint(model)+"/chat/completions").headers(h -> h.setBearerAuth(model.apiKey())).contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchangeToFlux(response -> {
                if(!response.statusCode().is2xxSuccessful())return response.releaseBody().thenMany(Flux.error(httpError(response.statusCode().value())));
                if(response.headers().contentType().orElse(MediaType.APPLICATION_JSON).isCompatibleWith(MediaType.TEXT_EVENT_STREAM))return response.bodyToFlux(String.class).filter(v -> !v.isBlank()&&!v.trim().equals("[DONE]")).map(json::readTree);
                return response.bodyToMono(JsonNode.class).flux();
            });
            List<String> output=frames.doOnNext(frame -> {if(frame.path("usage").isObject())usage.set(frame);if(frame.path("id").isString())providerId.set(frame.path("id").asString());})
                    .map(ModelCallClient::text).filter(v -> !v.isEmpty()).timeout(Mono.delay(first),v -> Mono.delay(idle))
                    .doOnNext(text -> {if(!backup)primaryOutput.set(true);if("CHAT".equals(mode)){boolean replacement=backup&&firstBackup.getAndSet(false)&&replace;events.accept(new GenerationEvent(replacement?"answer_replace":"answer_delta",backup?2:1,Map.of("text",text)));}})
                    .collectList().timeout(timeout).block(timeout.plusSeconds(2));
            String answer=output==null?"":String.join("",output);if(answer.isBlank())throw new ModelCallException("EMPTY_OUTPUT",true,0);
            JsonNode measured=usage.get();if(measured==null)throw protocol("MISSING_USAGE");long input=token(measured,"prompt_tokens"),tokens=token(measured,"completion_tokens");
            var completed=new Completion(answer,input,tokens,model.model(),backup,Math.addExact(Math.addExact(input,tokens),unknownTokens),unknownTokens);save(i,id,role,completed,input,tokens,providerId.get(),generation);recordTokens(model.capability(),input,tokens);succeeded=true;committed(events,completed);return completed;
        }catch(Exception error){throw unknown(i,id,role,error,generation,dispatched);}
        finally {recordDuration(model.capability(),role,succeeded,started);}
    }
    private GroupState admit(ChatIdentity i,String id,String hash,String capability,boolean foreground) {
        if(i==null||!i.authenticated()||id==null||!id.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("模型调用身份或 ID 无效");
        long until=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        for(;;){checkCancelled();GroupState g=billing.reserveGroup(i,new GroupReserve(id,hash,capability,foreground)).block(Duration.ofSeconds(6));if(g==null)throw protocol("BILLING_UNAVAILABLE");if(!"QUEUED".equals(g.status()))return g;if(System.nanoTime()>=until)throw new ModelCallException("ADMISSION_TIMEOUT",false,0);try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ModelCallException("CANCELLED",false,0);}}
    }
    private void begin(ChatIdentity i,String id,AuthModelClient.SelectedModel model,String role,int generation,long upper) {
        checkCancelled();var a=billing.attempt(i,id,new AttemptReserve(role,model.provider().name(),model.model(),endpoint(model),model.credentialId()==null?"platform":model.credentialId(),upper)).block(Duration.ofSeconds(6));
        if(a==null||!"RESERVED".equals(a.status())||a.generation()!=generation)throw new ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);
        billing.dispatchAttempt(i,id,role,generation).block(Duration.ofSeconds(6));checkCancelled();
    }
    private void save(ChatIdentity i,String id,String role,Object result,long input,long output,String providerId,int generation) {billing.receipt(i,id,new AttemptReceipt(role,json.writeValueAsString(result),input,output,providerId,generation)).block(Duration.ofSeconds(6));billing.finish(i,id).block(Duration.ofSeconds(6));}
    private <T> T replay(ChatIdentity i,String id,GroupState g,Class<T> type) {if(!"SUCCEEDED".equals(g.status()))billing.finish(i,id).block(Duration.ofSeconds(6));return type==Completion.class?type.cast(completionFromReceipt(g.receipt())):json.readValue(g.receipt(),type);}
    /** Cached receipts from the original five-field protocol retain their actual accounting. */
    public static Completion completionFromReceipt(String receipt) {
        JsonNode node=JsonMapper.builder().build().readTree(receipt);
        long input=node.path("inputTokens").asLong(),output=node.path("outputTokens").asLong();
        return new Completion(node.path("text").asString(),input,output,node.path("model").asString(),node.path("fallback").asBoolean(),
                node.path("reservedTokens").asLong(Math.addExact(input,output)),node.path("unknownTokens").asLong(0));
    }
    private static void requireUndispatched(GroupState g) {if(!"RESERVED".equals(g.status()))throw new ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);}
    private AuthModelClient.SelectedModel requireModel(ChatIdentity i,ModelCapability c) {var m=models.resolve(i,c);if(m==null||m.apiKey()==null||m.apiKey().isBlank())throw new ModelCallException("MODEL_NOT_CONFIGURED",false,503);return m;}
    private JsonNode postJson(AuthModelClient.SelectedModel model,String path,Object request,Duration timeout) {return http.post().uri(endpoint(model)+path).headers(h -> h.setBearerAuth(model.apiKey())).contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchangeToMono(response -> response.statusCode().is2xxSuccessful()?response.bodyToMono(JsonNode.class):response.releaseBody().then(Mono.error(httpError(response.statusCode().value())))).timeout(timeout).block(timeout.plusSeconds(2));}
    private ModelCallException unknown(ChatIdentity i,String id,String role,Exception error,int generation,boolean dispatched) {boolean interrupted=Thread.interrupted();try{if(dispatched)billing.unknownAttempt(i,id,role,generation).block(Duration.ofSeconds(6));else billing.cancelGroup(i,id,generation).block(Duration.ofSeconds(6));}catch(Exception ignored){}finally{if(interrupted)Thread.currentThread().interrupt();}if(dispatched)metrics.counter("ai.model.unknown","role",role).increment();if(interrupted||error instanceof java.util.concurrent.CancellationException)return new ModelCallException("CANCELLED",false,0);if(error instanceof ModelCallException e)return e;if(error instanceof org.springframework.web.server.ResponseStatusException response)return new ModelCallException(response.getStatusCode().value()==402?"MODEL_PAYMENT_BUDGET":"BILLING_RECONCILIATION",false,response.getStatusCode().value()==402?402:409);return new ModelCallException("PROVIDER_TIMEOUT_OR_CONNECTION",dispatched,dispatched?0:409);}
    private void recordDuration(ModelCapability capability,String role,boolean succeeded,long started){metrics.timer("ai.model.duration","capability",capability==ModelCapability.FALLBACK?"CHAT":capability.name(),"role",role,"outcome",succeeded?"success":"failure").record(Duration.ofNanos(System.nanoTime()-started));}
    private void recordTokens(ModelCapability capability,long input,long output) {String name=capability==ModelCapability.FALLBACK?"CHAT":capability.name();metrics.counter("ai.model.tokens","direction","input","capability",name).increment(input);metrics.counter("ai.model.tokens","direction","output","capability",name).increment(output);}
    private void requireTokenBudget(ChatIdentity i,String id,GroupState g,Long budget,long bound){if(budget!=null&&budget<bound){try{billing.cancelGroup(i,id,g.generation()).block(Duration.ofSeconds(6));}catch(Exception ignored){}throw new ModelCallException("MODEL_TOKEN_BUDGET",false,429);}}
    public static GenerationEvent committedEvent(Completion result) {return new GenerationEvent("answer_committed",result.fallback()?2:1,Map.of("text",result.text(),"inputTokens",result.inputTokens(),"outputTokens",result.outputTokens(),"model",result.model(),"fallback",result.fallback(),"reservedTokens",result.reservedTokens(),"unknownTokens",result.unknownTokens()));}
    private static void committed(Consumer<GenerationEvent> events,Completion result) {events.accept(committedEvent(result));}
    private static ModelCallException httpError(int status) {return new ModelCallException("PROVIDER_ERROR",status==401||status==429||status>=500,status);}
    private static ModelCallException protocol(String reason) {return new ModelCallException(reason,false,409);}
    private static String text(JsonNode node) {JsonNode c=node.path("choices").path(0);String delta=c.path("delta").path("content").asString(null);return delta!=null?delta:c.path("message").path("content").asString("");}
    private static long token(JsonNode node,String field) {JsonNode value=node==null?null:node.path("usage").path(field);if(value==null||!value.isIntegralNumber()||value.asLong(-1)<0)throw protocol("MISSING_USAGE");return value.asLong();}
    private static String endpoint(AuthModelClient.SelectedModel m) {String url=m.baseUrl();if(url==null||url.isBlank())url=ModelProviderRegistry.defaultRegistry().resolve(m.provider(),m.capability(),m.model()).defaultBaseUrl();return url.replaceAll("/+$","");}
    private String hash(Object params) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(params)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void requireText(String value,int max) {requireText(value,max,false);}
    private static void requireText(String value,int max,boolean emptyAllowed) {if(value==null||!emptyAllowed&&value.isBlank()||value.codePointCount(0,value.length())>max)throw new IllegalArgumentException("模型输入为空或超出上限");}
    private static void checkCancelled() {if(Thread.currentThread().isInterrupted())throw new ModelCallException("CANCELLED",false,0);}
    public static class ModelCallException extends RuntimeException {
        private final String code;private final boolean retryable;private final int status;
        public ModelCallException(String code,boolean retryable,int status){super("当前回答服务暂时不可用，你的问题和处理进度已保留。可以稍后重试。");this.code=code;this.retryable=retryable;this.status=status;}
        public String code(){return code;}public int status(){return status;}
    }
}
