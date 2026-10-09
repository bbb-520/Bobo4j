package com.bbb.exercise.agentdemo.ragservice.processing;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.GroupState;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.api.model.TokenBudgetEstimator;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.runtime.client.BillingClient;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongFunction;

/** Child receipts and token holds survive crashes; network IO never owns a database lock. */
@Component
public class RagModelGateway {
    private final ModelCallClient models;private final BillingClient billing;private final JdbcTemplate db;
    private com.bbb.exercise.agentdemo.runtime.client.AuthModelClient selection;
    private final Gson json=new Gson();private final TransactionTemplate tx;
    public RagModelGateway(ModelCallClient models,BillingClient billing,JdbcTemplate db){this(models,billing,db,db==null?null:new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));}
    public RagModelGateway(ModelCallClient models,BillingClient billing,JdbcTemplate db,PlatformTransactionManager manager){this.models=models;this.billing=billing;this.db=db;this.tx=manager==null?null:new TransactionTemplate(manager);}
    @Autowired public RagModelGateway(ModelCallClient models,BillingClient billing,JdbcTemplate db,PlatformTransactionManager manager,com.bbb.exercise.agentdemo.runtime.client.AuthModelClient selection){this(models,billing,db,manager);this.selection=selection;}
    public String embeddingFingerprint(ChatIdentity who,int dimensions){
        var model=selection==null?null:selection.resolve(who,com.bbb.exercise.agentdemo.api.model.ModelCapability.EMBEDDING);
        if(model==null||model.credentialId()==null)throw new RagException("MODEL_NOT_CONFIGURED",HttpStatus.SERVICE_UNAVAILABLE);
        return com.bbb.exercise.agentdemo.api.model.ModelFingerprint.embedding(model.provider().name(),model.model(),model.baseUrl(),model.credentialId(),dimensions);
    }
    public Completion complete(ChatIdentity who,String composite,String call,String mode,String system,String user,boolean fallback){
        long primary=TokenBudgetEstimator.completion(mode,system,user,false);
        long desired=fallback?Math.addExact(primary,TokenBudgetEstimator.completion(mode,system,user,true)):primary;
        return invoke(who,composite,call,mode,List.of(mode,system,user,fallback),Completion.class,primary,desired,limit->models.complete(who,call,mode,system,user,fallback,limit));
    }
    public EmbeddingBatch embed(ChatIdentity who,String composite,String call,List<String> texts,int dimensions){long upper=TokenBudgetEstimator.embedding(texts);return invoke(who,composite,call,"EMBEDDING",List.of(texts,dimensions),EmbeddingBatch.class,upper,upper,limit->models.embed(who,call,texts,dimensions,limit));}
    public Ranking rerank(ChatIdentity who,String composite,String call,String query,List<String> docs){long upper=TokenBudgetEstimator.rerank(query,docs);return invoke(who,composite,call,"RERANK",List.of(query,docs),Ranking.class,upper,upper,limit->models.rerank(who,call,query,docs,limit));}
    public void ensureBudget(ChatIdentity who,String composite,Long limit){
        requireId(composite);long value=limit==null?64000:limit;
        if(value<0||value>64000)throw new RagException("MODEL_TOKEN_BUDGET",HttpStatus.TOO_MANY_REQUESTS);
        // The caller supplies additional available tokens after accounting for already consumed/held usage.
        db.update("INSERT IGNORE INTO rag_call_budget(tenant_id,user_id,composite_id,token_limit) VALUES(?,?,?,?)",who.tenantId(),who.userId(),composite,value);
        db.update("UPDATE rag_call_budget SET token_limit=LEAST(token_limit,actual_tokens+held_tokens+?) WHERE tenant_id=? AND user_id=? AND composite_id=?",value,who.tenantId(),who.userId(),composite);
    }
    public <T>T withBudget(ChatIdentity who,String composite,Long limit,Supplier<T> action){ensureBudget(who,composite,limit);return action.get();}
    public Budget budget(ChatIdentity who,String composite){
        var rows=db.queryForList("SELECT token_limit,actual_tokens,held_tokens FROM rag_call_budget WHERE tenant_id=? AND user_id=? AND composite_id=?",who.tenantId(),who.userId(),composite);
        if(rows.isEmpty())return new Budget(0,0,0);var row=rows.getFirst();return new Budget(number(row,"token_limit"),number(row,"actual_tokens"),number(row,"held_tokens"));
    }
    public Accounting accounting(ChatIdentity who,String composite){
        var row=db.queryForMap("SELECT COALESCE(SUM(input_tokens),0) AS inputs,COALESCE(SUM(output_tokens),0) AS outputs,COALESCE(SUM(reserved_tokens),0) AS unknowns FROM rag_model_call WHERE tenant_id=? AND user_id=? AND composite_id=?",who.tenantId(),who.userId(),composite);
        return new Accounting(number(row,"inputs"),number(row,"outputs"),number(row,"unknowns"));
    }
    private Reservation reserve(ChatIdentity who,String composite,String call,long minimum,long desired){
        return tx.execute(s->{var budgets=lockBudget(who,composite);var child=lockChild(who,call);
            long input=number(child,"input_tokens"),output=number(child,"output_tokens"),held=number(child,"reserved_tokens");
            // A still pending generation already owns its reservation. Resolved generations must acquire a new one.
            if(List.of("PENDING","UNKNOWN").contains(child.get("status"))&&held>0)return new Reservation(input,output,Math.max(0,held-number(child,"attempt_reservation")),number(child,"attempt_reservation"));
            long available=64000;
            if(!budgets.isEmpty()){var b=budgets.getFirst();available=number(b,"token_limit")-Math.addExact(number(b,"actual_tokens"),number(b,"held_tokens"));}
            if(minimum>available)throw new RagException("MODEL_TOKEN_BUDGET",HttpStatus.TOO_MANY_REQUESTS);
            long estimate=desired<=available?desired:minimum;
            if(!budgets.isEmpty()){
                var b=budgets.getFirst();long total=Math.addExact(number(b,"actual_tokens"),number(b,"held_tokens"));
                if(estimate>number(b,"token_limit")-total)throw new RagException("MODEL_TOKEN_BUDGET",HttpStatus.TOO_MANY_REQUESTS);
                db.update("UPDATE rag_call_budget SET held_tokens=held_tokens+?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND composite_id=?",estimate,who.tenantId(),who.userId(),composite);
            }
            db.update("UPDATE rag_model_call SET status='PENDING',reserved_tokens=reserved_tokens+?,attempt_reservation=?,budget_settled=FALSE WHERE tenant_id=? AND user_id=? AND call_id=?",estimate,estimate,who.tenantId(),who.userId(),call);
            return new Reservation(input,output,held,estimate);
        });
    }
    private void account(ChatIdentity who,String composite,String call,long input,long output,long unknown,String status,String receipt){
        if(input<0||output<0||unknown<0)throw new RagException("MODEL_USAGE_INVALID",HttpStatus.BAD_GATEWAY);
        tx.executeWithoutResult(s->{var budgets=lockBudget(who,composite);var child=lockChild(who,call);
            long previous=Math.addExact(number(child,"input_tokens"),number(child,"output_tokens")),actual=Math.addExact(input,output),held=number(child,"reserved_tokens");
            // Auth is authoritative for cumulative generation accounting. Never erase already measured usage.
            if(actual<previous)return;
            if(!budgets.isEmpty())db.update("UPDATE rag_call_budget SET actual_tokens=actual_tokens+?,held_tokens=held_tokens-?+?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND composite_id=?",actual-previous,held,unknown,who.tenantId(),who.userId(),composite);
            db.update("UPDATE rag_model_call SET status=?,receipt_json=COALESCE(?,receipt_json),input_tokens=?,output_tokens=?,reserved_tokens=?,attempt_reservation=0,budget_settled=TRUE,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND call_id=?",status,receipt,input,output,unknown,who.tenantId(),who.userId(),call);
        });
    }
    private <T>T invoke(ChatIdentity who,String composite,String call,String capability,Object request,Class<T> type,long minimum,long desired,LongFunction<T> action){
        requireId(composite);requireId(call);String hash=SemanticChunker.hash(json.toJson(request));
        db.update("INSERT IGNORE INTO rag_model_call(tenant_id,user_id,call_id,composite_id,request_hash,capability,status) VALUES(?,?,?,?,?,?,'PENDING')",who.tenantId(),who.userId(),call,composite,hash,capability);
        var row=child(who,call);
        if(!hash.equals(row.get("request_hash"))||!composite.equals(row.get("composite_id")))throw new RagException("MODEL_CALL_PARAMETER_CONFLICT",HttpStatus.CONFLICT);
        if(row.get("receipt_json")!=null)return json.fromJson((String)row.get("receipt_json"),type);
        if(!"PENDING".equals(row.get("status"))){if(reconcile(who,composite,row))throw new ModelCallClient.ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);row=child(who,call);if(row.get("receipt_json")!=null)return json.fromJson((String)row.get("receipt_json"),type);}
        Reservation reserved=reserve(who,composite,call,minimum,desired);
        try{
            T receipt=action.apply(reserved.limit());long input,output;
            if(receipt instanceof Completion c){input=c.inputTokens();output=c.outputTokens();}
            else if(receipt instanceof EmbeddingBatch b){input=b.inputTokens();output=0;}
            else if(receipt instanceof Ranking r){input=r.inputTokens();output=0;}
            else throw new IllegalArgumentException("Receipt type");
            long unknown=receipt instanceof Completion c?c.unknownTokens():reserved.priorUnknown();
            account(who,composite,call,Math.addExact(reserved.input(),input),Math.addExact(reserved.output(),output),unknown,"RECEIVED",json.toJson(receipt));return receipt;
        }catch(RuntimeException error){db.update("UPDATE rag_model_call SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND call_id=? AND receipt_json IS NULL",who.tenantId(),who.userId(),call);throw error;}
    }
    /** A fee-resolved failure with unknown token usage keeps its upper bound. */
    private boolean reconcile(ChatIdentity who,String composite,Map<String,Object> child){
        String call=(String)child.get("call_id");GroupState group;
        try{group=billing.group(who,call).block(Duration.ofSeconds(6));}
        catch(ResponseStatusException error){if(error.getStatusCode().value()!=404)throw error;
            if(child.get("receipt_json")==null)account(who,composite,call,number(child,"input_tokens"),number(child,"output_tokens"),0,"FAILED_RESOLVED",null);
            return false;
        }
        if(group==null)throw new RagException("MODEL_ACCOUNTING_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
        boolean pending=group.receipt()==null&&Set.of("UNKNOWN","DISPATCHED","RUNNING").contains(group.status());
        long unknown=pending?Math.max(group.unknownTokens(),number(child,"reserved_tokens")):group.unknownTokens();
        String state=group.receipt()!=null?"RECEIVED":pending?"UNKNOWN":"FAILED_RESOLVED";
        account(who,composite,call,group.inputTokens(),group.outputTokens(),unknown,state,group.receipt());return pending;
    }
    public Map<String,Object> status(ChatIdentity who,String composite){
        requireId(composite);var children=db.queryForList("SELECT * FROM rag_model_call WHERE tenant_id=? AND user_id=? AND composite_id=?",who.tenantId(),who.userId(),composite);var pending=new ArrayList<String>();
        for(var child:children)if((child.get("receipt_json")==null||number(child,"reserved_tokens")>0)&&reconcile(who,composite,child))pending.add((String)child.get("call_id"));
        var answers=db.queryForList("SELECT status,answer_json,conversation_id FROM rag_answer WHERE tenant_id=? AND user_id=? AND call_id=?",who.tenantId(),who.userId(),composite);
        var retrievals=db.queryForList("SELECT payload_json FROM rag_stage_snapshot WHERE tenant_id=? AND user_id=? AND composite_id=? AND stage='retrieval-result'",who.tenantId(),who.userId(),composite);
        if(children.isEmpty()&&answers.isEmpty()&&retrievals.isEmpty())throw new RagException("RAG_CALL_NOT_FOUND",HttpStatus.NOT_FOUND);
        var result=new LinkedHashMap<String,Object>();var accounting=accounting(who,composite);
        result.put("inputTokens",accounting.inputTokens());result.put("outputTokens",accounting.outputTokens());result.put("unknownTokens",accounting.unknownTokens());result.put("pendingCallIds",pending);
        String conversation=answers.isEmpty()?null:(String)answers.getFirst().get("conversation_id");
        if(conversation==null&&!retrievals.isEmpty()){var context=db.queryForList("SELECT payload_json FROM rag_stage_snapshot WHERE tenant_id=? AND user_id=? AND composite_id=? AND stage='retrieval-conversation'",who.tenantId(),who.userId(),composite);if(!context.isEmpty())conversation=json.fromJson((String)context.getFirst().get("payload_json"),String.class);}
        boolean allowed=conversation==null?retrievals.isEmpty():accessible(who,conversation);
        if(!allowed){result.put("status","CANCELLED");result.put("error","DOCUMENT_OR_VERSION_UNAVAILABLE");}
        else if(!pending.isEmpty())result.put("status","UNKNOWN");
        else if(!answers.isEmpty()&&"COMPLETED".equals(answers.getFirst().get("status"))){result.put("status","SUCCEEDED");result.put("receipt",json.fromJson((String)answers.getFirst().get("answer_json"),Object.class));}
        else if(answers.isEmpty()&&!retrievals.isEmpty()){result.put("status","SUCCEEDED");result.put("receipt",json.fromJson((String)retrievals.getFirst().get("payload_json"),Object.class));}
        else result.put("status","FAILED_RESOLVED");
        return result;
    }
    private boolean accessible(ChatIdentity who,String conversation){var rows=db.queryForList("SELECT versions_json FROM rag_conversation WHERE conversation_id=? AND tenant_id=? AND user_id=?",conversation,who.tenantId(),who.userId());if(rows.isEmpty())return false;Map<String,Integer> versions=json.fromJson((String)rows.getFirst().get("versions_json"),new com.google.gson.reflect.TypeToken<Map<String,Integer>>(){}.getType());for(var entry:versions.entrySet()){var docs=db.queryForList("SELECT index_version,index_status,deleted FROM rag_document WHERE document_id=? AND tenant_id=? AND user_id=?",entry.getKey(),who.tenantId(),who.userId());if(docs.isEmpty()||Boolean.TRUE.equals(docs.getFirst().get("deleted"))||!"READY".equals(docs.getFirst().get("index_status"))||number(docs.getFirst(),"index_version")!=entry.getValue())return false;}return true;}
    private List<Map<String,Object>> lockBudget(ChatIdentity who,String composite){return db.queryForList("SELECT * FROM rag_call_budget WHERE tenant_id=? AND user_id=? AND composite_id=? FOR UPDATE",who.tenantId(),who.userId(),composite);}
    private Map<String,Object> lockChild(ChatIdentity who,String call){return db.queryForMap("SELECT * FROM rag_model_call WHERE tenant_id=? AND user_id=? AND call_id=? FOR UPDATE",who.tenantId(),who.userId(),call);}
    private Map<String,Object> child(ChatIdentity who,String call){return db.queryForMap("SELECT * FROM rag_model_call WHERE tenant_id=? AND user_id=? AND call_id=?",who.tenantId(),who.userId(),call);}
    private static long number(Map<String,Object> row,String key){return row.get(key)==null?0:((Number)row.get(key)).longValue();}
    private static void requireId(String id){if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,128}"))throw new RagException("MODEL_CALL_ID_INVALID",HttpStatus.BAD_REQUEST);}
    private record Reservation(long input,long output,long priorUnknown,long limit){}
    public record Budget(long limit,long actual,long held){public long accounted(){return Math.addExact(actual,held);}}
    public record Accounting(long inputTokens,long outputTokens,long unknownTokens){}
}
