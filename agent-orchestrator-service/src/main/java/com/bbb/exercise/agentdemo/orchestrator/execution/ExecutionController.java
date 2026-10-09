package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/agent-executions")
public class ExecutionController {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","STOPPED","WAITING_FOR_BUDGET","WAITING_FOR_RECONCILIATION","NEEDS_INPUT");
    private final ExecutionStore store;private final ExecutionIdentity identities;private final ExecutionClient client;
    public ExecutionController(ExecutionStore store,ExecutionIdentity identities,ExecutionClient client){this.store=store;this.identities=identities;this.client=client;}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ExecutionStore.View> create(@RequestBody ExecutionStore.Create input,ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i->work(()->store.create(i,input).view()));
    }
    @GetMapping public Mono<ExecutionStore.View> lookup(@RequestParam String requestId,ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i->work(()->store.byRequest(i,requestId).view()));
    }
    @GetMapping("/{id}") public Mono<ExecutionStore.View> get(@PathVariable String id,ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i->work(()->store.get(i,id).view()));
    }
    @PostMapping("/{id}/stop") public Mono<ExecutionStore.View> stop(@PathVariable String id,ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i->work(()->store.stop(i,id).view()));
    }
    @PostMapping("/{id}/resume") public Mono<ExecutionStore.View> resume(@PathVariable String id,@RequestBody ExecutionStore.Resume input,ServerWebExchange exchange) {
        return identities.require(exchange).flatMap(i->work(()->{
            var row=store.get(i,id);boolean reconciled=false;
            if(row.view().status().equals("WAITING_FOR_RECONCILIATION")||row.view().status().equals("WAITING_FOR_BUDGET")&&row.budget().reservedTokens()>0){verifyReconciled(i,id,row);reconciled=true;}
            return store.resume(i,id,input,reconciled).view();
        }));
    }
    private void verifyReconciled(ChatIdentity identity,String id,ExecutionStore.Row row) throws Exception {
        for(var step:store.reconcilableSteps(identity,id)) {
            boolean rag="rag".equals(step.target());
            String path=rag?"/internal/rag/calls/"+step.callId():"/internal/billing/users/"+identity.userId()+"/groups/"+step.callId();
            Map<String,Object> state;
            try{state=client.get(identity,rag?"rag":"auth",path).get(12,TimeUnit.SECONDS);}
            catch(java.util.concurrent.ExecutionException e){if(e.getCause() instanceof ExecutionClient.RemoteFailure r&&r.status==404)continue;throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"暂时无法确认模型调用状态");}
            if(!Set.of("SUCCEEDED","RECEIVED","CANCELLED","QUEUED","RESERVED","FAILED_RESOLVED").contains(String.valueOf(state.get("status"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"模型调用仍待核对，请稍后重试");
        }
    }
    @GetMapping(value="/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@PathVariable String id,
        @RequestHeader(value="Last-Event-ID",required=false)String lastEventId,
        @RequestParam(required=false)Long after,ServerWebExchange exchange) {
        long offset=cursor(id,lastEventId,after);
        return identities.require(exchange).flatMapMany(identity->work(()->{
            store.get(identity,id);store.events(identity,id,offset,1);return identity;
        }).flatMapMany(i->stream(i,id,offset)));
    }
    Flux<ServerSentEvent<Object>> stream(ChatIdentity identity,String id,long after) {
        var cursor=new AtomicLong(after);
        Flux<ServerSentEvent<Object>> data=Flux.interval(Duration.ZERO,Duration.ofMillis(500))
            .concatMap(t->work(()->{
                var events=store.events(identity,id,cursor.get(),200);var view=store.get(identity,id).view();
                boolean done=TERMINAL.contains(view.status())&&(events.isEmpty()||events.getLast().seq()==view.lastSeq());
                return new Batch(events,done);
            })).takeUntil(Batch::done).concatMap(batch->Flux.fromIterable(batch.events()))
            .doOnNext(e->cursor.set(e.seq()))
            .map(e->ServerSentEvent.<Object>builder(e).event(e.type()).id(e.executionId()+":"+e.seq()).build());
        return data.publish(shared->Flux.merge(shared,Flux.interval(Duration.ofSeconds(15))
                .map(t->ServerSentEvent.<Object>builder().comment("heartbeat").build()).takeUntilOther(shared.ignoreElements())));
    }
    static long cursor(String id,String header,Long query) {
        if(header!=null&&!header.isBlank()){
            int colon=header.lastIndexOf(':');if(colon<0||!header.substring(0,colon).equals(id))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"事件游标与任务不一致");
            try{long n=Long.parseLong(header.substring(colon+1));if(n<0)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"事件游标无效");}
        }
        if(query!=null&&query<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"事件游标无效");return query==null?0:query;
    }
    private static <T> Mono<T> work(java.util.concurrent.Callable<T> action){return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());}
    private record Batch(List<ExecutionStore.Event> events,boolean done) {}
}
