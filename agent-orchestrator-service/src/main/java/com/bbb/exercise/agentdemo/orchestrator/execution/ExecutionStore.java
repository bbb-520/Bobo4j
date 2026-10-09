package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Owns execution state. Every worker write is fenced under the same row lock as event sequence allocation. */
@Service
public class ExecutionStore {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ObjectMapper json;
    public ExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json;
    }
    public record Create(String requestId,String type,String question,String conversationId,
                         String documentConversationId,Boolean allowImageGeneration) {}
    public record Resume(String requestId,long expectedVersion,Integer additionalRounds,String instruction) {}
    public record View(String executionId,String requestId,String type,String status,long version,long lastSeq,
                       String question,String answer,List<Map<String,Object>> sources,String error,
                       int roundsUsed,int roundLimit,long tokensUsed,int toolCalls,int generationVersion,long reservedTokens) {}
    public record Row(View view,ChatIdentity identity,Create input,ExecutionBudget budget,String leaseToken,
                      Instant leaseUntil,boolean cancelled,int inputRevision) {}
    public record Lease(String executionId,String token,ChatIdentity identity) {}
    public record Event(String executionId,long seq,String type,int generationVersion,Map<String,Object> payload) {}
    public record Step(String id,String key,String hash,String callId,String status,Map<String,Object> result,long activeMillis,String target,String path,String requestJson,String consumption,String toolName) {}
    public Row create(ChatIdentity identity,Create input) {
        authenticated(identity);validate(input);
        var normalized=new Create(input.requestId(),input.type().toUpperCase(Locale.ROOT),input.question().trim(),
                input.conversationId(),input.documentConversationId(),Boolean.TRUE.equals(input.allowImageGeneration()));
        String hash=hash(write(normalized));
        return tx.execute(s->{
            var now=now();String id=UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO agent_execution(id,tenant_id,user_id,request_id,payload_hash,type,question,input_json,
                status,budget_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,id,identity.tenantId(),identity.userId(),normalized.requestId(),hash,normalized.type(),
                    normalized.question(),write(normalized),"QUEUED",write(ExecutionBudget.initial()),stamp(now),stamp(now));
            var rows=jdbc.query("SELECT * FROM agent_execution WHERE tenant_id=? AND user_id=? AND request_id=? FOR UPDATE",mapper(),identity.tenantId(),identity.userId(),normalized.requestId());
            if(rows.isEmpty())throw conflict("幂等请求冲突");var existing=rows.getFirst();
            if(!existing.view().executionId().equals(id)){samePayload(existing.view().executionId(),hash);return existing;}
            appendLocked(id,"status",Map.of("status","QUEUED"));return get(identity,id);
        });
    }
    public Row byRequest(ChatIdentity identity,String requestId) {
        authenticated(identity);safeKey(requestId);var row=findRequest(identity,requestId);
        if(row==null)throw missing();return row;
    }
    private Row findRequest(ChatIdentity i,String requestId) {
        var rows=jdbc.query("SELECT * FROM agent_execution WHERE tenant_id=? AND user_id=? AND request_id=?",mapper(),i.tenantId(),i.userId(),requestId);
        if(rows.isEmpty())return null;assertOwner(i,rows.getFirst());return rows.getFirst();
    }
    public Row get(ChatIdentity identity,String id) {
        authenticated(identity);uuid(id);
        var rows=jdbc.query("SELECT * FROM agent_execution WHERE id=? AND tenant_id=? AND user_id=?",mapper(),id,identity.tenantId(),identity.userId());
        if(rows.isEmpty())throw missing();assertOwner(identity,rows.getFirst());return rows.getFirst();
    }
    public Optional<Lease> claim(String worker) {
        return tx.execute(s->{
            Instant at=now();
            var ids=jdbc.queryForList("""
                SELECT id FROM agent_execution WHERE status IN ('QUEUED','RUNNING','STOPPING')
                AND (lease_until IS NULL OR lease_until<?) ORDER BY created_at LIMIT 20
                """,String.class,stamp(at));
            for(String id:ids){String token=UUID.randomUUID().toString();
                int changed=jdbc.update("""
                    UPDATE agent_execution SET lease_owner=?,lease_token=?,lease_until=?,
                    status=CASE WHEN cancel_requested=TRUE THEN 'STOPPING' ELSE 'RUNNING' END,
                    version=version+1,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING','STOPPING')
                    AND (lease_until IS NULL OR lease_until<?)
                    """,worker,token,stamp(at.plusSeconds(30)),stamp(at),id,stamp(at));
                if(changed==1){var row=raw(id);return Optional.of(new Lease(id,token,row.identity()));}
            }
            return Optional.empty();
        });
    }
    public Row leased(Lease lease) {
        var row=get(lease.identity(),lease.executionId());checkLease(row,lease);return row;
    }
    public boolean renew(Lease lease) {
        Instant at=now();
        return jdbc.update("UPDATE agent_execution SET lease_until=? WHERE id=? AND lease_token=? AND lease_until>=? AND status IN ('RUNNING','STOPPING')",
                stamp(at.plusSeconds(30)),lease.executionId(),lease.token(),stamp(at))==1;
    }
    public void event(Lease lease,String type,Map<String,Object> payload) {
        tx.executeWithoutResult(s->{lock(lease);appendLocked(lease.executionId(),type,payload);});
    }
    public void streamEvent(Lease lease,String type,int generationVersion,Map<String,Object> payload) {
        if(!Set.of("answer_delta","answer_replace","fallback_started","progress").contains(type))throw bad("生成事件类型无效");
        tx.executeWithoutResult(s->{var row=lock(lease);if(row.cancelled())throw conflict("停止请求已生效");
            if(generationVersion<row.view().generationVersion())return;
            if(generationVersion>row.view().generationVersion()+1)throw conflict("答案版本不连续");
            String text=payload.get("text") instanceof String value?value:"";
            String answer=row.view().answer()==null?"":row.view().answer();
            if(type.equals("answer_replace"))answer=text;
            else if(type.equals("answer_delta"))answer+=text;
            jdbc.update("UPDATE agent_execution SET answer=?,generation_version=? WHERE id=?",answer,generationVersion,lease.executionId());
            appendLocked(lease.executionId(),type,payload);
        });
    }
    public Step beginStep(Lease lease,String key,String parameterHash,String callId) {
        return beginStep(lease,key,parameterHash,callId,null,null,null,null,null);
    }
    public Step beginStep(Lease lease,String key,String parameterHash,String callId,String target,String path,Object request,String consumption,String toolName) {
        return tx.execute(s->{lock(lease);var old=steps(lease.executionId(),key);
            if(!old.isEmpty()){var step=old.getFirst();if(!step.hash().equals(parameterHash)||!step.callId().equals(callId))throw conflict("步骤参数不一致");return step;}
            String id=UUID.randomUUID().toString();var at=now();
            String body=request==null?null:write(request);
            jdbc.update("INSERT INTO agent_execution_step(id,execution_id,step_key,parameter_hash,call_id,status,target,request_path,request_json,consumption,tool_name,created_at,updated_at) VALUES(?,?,?,?,?,'DISPATCHED',?,?,?,?,?,?,?)",
                    id,lease.executionId(),key,parameterHash,callId,target,path,body,consumption,toolName,stamp(at),stamp(at));
            return new Step(id,key,parameterHash,callId,"DISPATCHED",Map.of(),0,target,path,body,consumption,toolName);
        });
    }
    public void finishStep(Lease lease,Step step,Map<String,Object> result,ExecutionBudget budget) {
        tx.executeWithoutResult(s->{lock(lease);
            jdbc.update("UPDATE agent_execution_step SET status='SUCCEEDED',result_json=?,updated_at=? WHERE id=? AND execution_id=? AND status='DISPATCHED'",write(result),stamp(now()),step.id(),lease.executionId());
            saveBudgetLocked(lease.executionId(),budget);
        });
    }
    /** Cumulative attempt time, flushed on heartbeats and on every success/failure/cancellation. */
    public void accountStepTime(Lease lease,Step step,long attemptMillis) {
        if(attemptMillis<0)throw bad("执行时长无效");
        tx.executeWithoutResult(s->{var row=lock(lease);
            long stored=jdbc.queryForObject("SELECT active_millis FROM agent_execution_step WHERE id=? AND execution_id=?",Long.class,step.id(),lease.executionId());
            long total=Math.addExact(step.activeMillis(),attemptMillis),delta=Math.max(0,total-stored);
            if(delta>0){jdbc.update("UPDATE agent_execution_step SET active_millis=?,updated_at=? WHERE id=?",total,stamp(now()),step.id());saveBudgetLocked(lease.executionId(),row.budget().charge(0,0,delta));}
        });
    }
    public long reserveStepTokens(Lease lease,Step step) {
        return tx.execute(s->{var row=lock(lease);long held=jdbc.queryForObject("SELECT reserved_tokens FROM agent_execution_step WHERE id=? AND execution_id=?",Long.class,step.id(),lease.executionId());
            if(held>0)return held;long remaining=row.budget().availableTokens();if(remaining<=0)throw conflict("剩余token预算不足");
            jdbc.update("UPDATE agent_execution_step SET reserved_tokens=? WHERE id=?",remaining,step.id());saveBudgetLocked(lease.executionId(),row.budget().reserve(remaining));return remaining;});
    }
    public void settleStepTokens(Lease lease,Step step,long input,long output,long unknown) {
        if(input<0||output<0||unknown<0)throw bad("模型用量无效");
        tx.executeWithoutResult(s->{var row=lock(lease);var saved=jdbc.queryForMap("SELECT accounted_input_tokens,accounted_output_tokens,reserved_tokens,unknown_tokens FROM agent_execution_step WHERE id=? AND execution_id=?",step.id(),lease.executionId());
            long oldInput=((Number)saved.get("accounted_input_tokens")).longValue(),oldOutput=((Number)saved.get("accounted_output_tokens")).longValue();
            long held=((Number)saved.get("reserved_tokens")).longValue(),oldUnknown=((Number)saved.get("unknown_tokens")).longValue();
            long settledInput=Math.max(input,oldInput),settledOutput=Math.max(output,oldOutput);
            var budget=row.budget().charge(settledInput-oldInput,settledOutput-oldOutput,0).withReserved(row.budget().reservedTokens()-held-oldUnknown+unknown);
            jdbc.update("UPDATE agent_execution_step SET accounted_input_tokens=?,accounted_output_tokens=?,unknown_tokens=?,reserved_tokens=0 WHERE id=?",settledInput,settledOutput,unknown,step.id());saveBudgetLocked(lease.executionId(),budget);
        });
    }
    public void saveBudget(Lease lease,ExecutionBudget budget) {tx.executeWithoutResult(s->{lock(lease);saveBudgetLocked(lease.executionId(),budget);});}
    private void saveBudgetLocked(String id,ExecutionBudget budget) {
        var at=now();jdbc.update("UPDATE agent_execution SET budget_json=?,updated_at=? WHERE id=?",write(budget),stamp(at),id);
        jdbc.update("DELETE FROM agent_execution_checkpoint WHERE execution_id=?",id);
        jdbc.update("INSERT INTO agent_execution_checkpoint(execution_id,version,state_json,updated_at) SELECT id,version,?,? FROM agent_execution WHERE id=?",write(budget),stamp(at),id);
    }
    public List<Step> completedSteps(Lease lease) {
        leased(lease);return jdbc.query("SELECT * FROM agent_execution_step WHERE execution_id=? AND status='SUCCEEDED' ORDER BY created_at,id",stepMapper(),lease.executionId());
    }
    private List<Step> steps(String id,String key) {return jdbc.query("SELECT * FROM agent_execution_step WHERE execution_id=? AND step_key=?",stepMapper(),id,key);}
    public void complete(Lease lease,String text,List<Map<String,Object>> sources,String evaluation) {
        complete(lease,text,sources,evaluation,null);
    }
    public void complete(Lease lease,String text,List<Map<String,Object>> sources,String evaluation,ExecutionBudget budget) {
        tx.executeWithoutResult(s->{var row=lock(lease);if(row.cancelled())throw conflict("停止请求已生效");
            if(budget!=null)saveBudgetLocked(lease.executionId(),budget);
            jdbc.update("UPDATE agent_execution SET answer=?,sources_json=?,error=NULL WHERE id=?",text,write(sources),lease.executionId());
            appendLocked(lease.executionId(),"answer_committed",Map.of("text",text,"sources",sources,"evaluation",evaluation));
            terminalLocked(lease.executionId(),"COMPLETED",null);
        });
    }
    public void terminal(Lease lease,String status,String message) {tx.executeWithoutResult(s->{lock(lease);terminalLocked(lease.executionId(),status,message);});}
    public void terminalWithBudget(Lease lease,String status,String message,ExecutionBudget budget) {tx.executeWithoutResult(s->{lock(lease);saveBudgetLocked(lease.executionId(),budget);terminalLocked(lease.executionId(),status,message);});}
    private void terminalLocked(String id,String status,String message) {
        jdbc.update("UPDATE agent_execution SET status=?,error=?,lease_until=NULL,lease_token=NULL,lease_owner=NULL,version=version+1,updated_at=? WHERE id=?",status,message,stamp(now()),id);
        var payload=new LinkedHashMap<String,Object>();payload.put("status",status);if(message!=null)payload.put("error",message);
        appendLocked(id,"status",payload);
    }
    public Row stop(ChatIdentity identity,String id) {
        return tx.execute(s->{var row=lockOwned(identity,id);if(!Set.of("QUEUED","RUNNING","STOPPING").contains(row.view().status()))return row;
            jdbc.update("UPDATE agent_execution SET cancel_requested=TRUE,version=version+1,updated_at=? WHERE id=?",stamp(now()),id);
            if(row.view().status().equals("QUEUED"))terminalLocked(id,"STOPPED",null);
            else {jdbc.update("UPDATE agent_execution SET status='STOPPING' WHERE id=?",id);appendLocked(id,"status",Map.of("status","STOPPING"));}
            return get(identity,id);
        });
    }
    public Row resume(ChatIdentity identity,String id,Resume resume) {
        return resume(identity,id,resume,false);
    }
    public Row resume(ChatIdentity identity,String id,Resume resume,boolean reconciled) {
        safeKey(resume.requestId());if(resume.instruction()!=null&&resume.instruction().length()>8000)throw bad("补充指令过长");
        String h=hash(write(resume));
        return tx.execute(s->{var row=lockOwned(identity,id);
            var prior=jdbc.queryForList("SELECT payload_hash FROM agent_execution_resume WHERE execution_id=? AND request_id=?",String.class,id,resume.requestId());
            if(!prior.isEmpty()){if(!prior.getFirst().equals(h))throw conflict("恢复幂等参数不一致");return row;}
            if(row.view().version()!=resume.expectedVersion())throw conflict("任务版本已变化");
            if(!Set.of("STOPPED","WAITING_FOR_BUDGET","NEEDS_INPUT","FAILED","WAITING_FOR_RECONCILIATION").contains(row.view().status()))throw conflict("任务当前不可恢复");
            if(row.view().status().equals("WAITING_FOR_RECONCILIATION")&&!reconciled)throw conflict("模型调用仍待对账，请稍后重试");
            var budget=row.budget().extend(resume.additionalRounds()==null?4:resume.additionalRounds());
            if(budget.admit().reason().equals("WAITING_FOR_BUDGET")&&!(reconciled&&budget.tokensUsed()<64000&&budget.activeMillis()<1800000&&budget.roundsUsed()<64&&budget.toolCalls()<128))throw conflict("已达到硬预算上限");
            jdbc.update("INSERT INTO agent_execution_resume(execution_id,request_id,payload_hash) VALUES(?,?,?)",id,resume.requestId(),h);
            if(resume.instruction()!=null&&!resume.instruction().isBlank()){
                var input=row.input();var amended=new Create(input.requestId(),input.type(),input.question()+"\n用户补充："+resume.instruction(),input.conversationId(),input.documentConversationId(),input.allowImageGeneration());
                jdbc.update("UPDATE agent_execution SET input_json=?,question=? WHERE id=?",write(amended),amended.question(),id);
                budget=new ExecutionBudget(budget.roundsUsed(),budget.roundLimit(),budget.toolCalls(),budget.tokensUsed(),budget.activeMillis(),"",0,true,budget.reservedTokens());
            }
            int generationChange=resume.instruction()!=null&&!resume.instruction().isBlank()?1:0;
            jdbc.update("UPDATE agent_execution SET status='QUEUED',cancel_requested=FALSE,budget_json=?,generation_version=generation_version+?,input_revision=input_revision+?,version=version+1,error=NULL,updated_at=? WHERE id=?",write(budget),generationChange,generationChange,stamp(now()),id);
            appendLocked(id,"status",Map.of("status","QUEUED"));return get(identity,id);
        });
    }
    public List<Event> events(ChatIdentity identity,String id,long after,int limit) {
        var row=get(identity,id);if(after<0||after>row.view().lastSeq())throw bad("事件游标无效");
        Long floor=jdbc.queryForObject("SELECT MIN(seq) FROM agent_execution_event WHERE execution_id=?",Long.class,id);
        if(floor!=null&&after<floor-1||floor==null&&after<row.view().lastSeq())throw new ResponseStatusException(HttpStatus.GONE,"事件已过期，请读取任务快照");
        return jdbc.query("SELECT * FROM agent_execution_event WHERE execution_id=? AND seq>? ORDER BY seq LIMIT ?",(rs,n)->new Event(id,rs.getLong("seq"),rs.getString("type"),rs.getInt("generation_version"),map(rs.getString("payload_json"))),id,after,Math.clamp(limit,1,200));
    }
    public List<Step> unfinishedSteps(ChatIdentity identity,String id) {
        get(identity,id);return jdbc.query("SELECT * FROM agent_execution_step WHERE execution_id=? AND status='DISPATCHED' ORDER BY created_at",stepMapper(),id);
    }
    public List<Step> reconcilableSteps(ChatIdentity identity,String id) {
        get(identity,id);return jdbc.query("SELECT * FROM agent_execution_step WHERE execution_id=? AND (status='DISPATCHED' OR unknown_tokens>0) ORDER BY created_at",stepMapper(),id);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${app.execution.retention-poll-ms:3600000}")
    public int pruneEvents() {
        Instant at=now();return jdbc.update("DELETE FROM agent_execution_event WHERE created_at<? AND execution_id IN (SELECT id FROM agent_execution WHERE status IN ('COMPLETED','FAILED','STOPPED') AND updated_at<?)",stamp(at.minusSeconds(30L*86400)),stamp(at.minusSeconds(7L*86400)));
    }
    private Row lock(Lease lease) {var row=lockOwned(lease.identity(),lease.executionId());checkLease(row,lease);return row;}
    private Row lockOwned(ChatIdentity i,String id) {
        authenticated(i);uuid(id);
        var rows=jdbc.query("SELECT * FROM agent_execution WHERE id=? AND tenant_id=? AND user_id=? FOR UPDATE",mapper(),id,i.tenantId(),i.userId());
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private void checkLease(Row row,Lease lease) {
        if(!lease.token().equals(row.leaseToken())||row.leaseUntil()==null||row.leaseUntil().isBefore(now())||!Set.of("RUNNING","STOPPING").contains(row.view().status()))throw conflict("执行租约已失效");
    }
    private void appendLocked(String id,String type,Map<String,Object> payload) {
        var row=raw(id);long seq=row.view().lastSeq()+1;var at=now();
        jdbc.update("UPDATE agent_execution SET last_seq=?,updated_at=? WHERE id=?",seq,stamp(at),id);
        jdbc.update("INSERT INTO agent_execution_event(execution_id,seq,type,generation_version,payload_json,created_at) VALUES(?,?,?,?,?,?)",id,seq,type,row.view().generationVersion(),write(payload),stamp(at));
        // Events themselves are durable; outbox records the final deliverable for downstream replay/audit.
        if(type.equals("answer_committed"))jdbc.update("INSERT INTO agent_execution_outbox(id,execution_id,event_seq,payload_json,status,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),id,seq,write(payload),"COMMITTED",stamp(at));
    }
    private Row raw(String id) {return jdbc.query("SELECT * FROM agent_execution WHERE id=?",mapper(),id).getFirst();}
    private RowMapper<Row> mapper() {
        return (rs,n)->{
            var b=json.readValue(rs.getString("budget_json"),ExecutionBudget.class);
            var sourceJson=rs.getString("sources_json");List<Map<String,Object>> sources=sourceJson==null?List.of():json.readValue(sourceJson,json.getTypeFactory().constructCollectionType(List.class,Map.class));
            var view=new View(rs.getString("id"),rs.getString("request_id"),rs.getString("type"),rs.getString("status"),rs.getLong("version"),rs.getLong("last_seq"),rs.getString("question"),rs.getString("answer"),sources,rs.getString("error"),b.roundsUsed(),b.roundLimit(),b.tokensUsed(),b.toolCalls(),rs.getInt("generation_version"),b.reservedTokens());
            Timestamp until=rs.getTimestamp("lease_until");
            return new Row(view,new ChatIdentity(rs.getString("tenant_id"),rs.getString("user_id"),true),json.readValue(rs.getString("input_json"),Create.class),b,rs.getString("lease_token"),until==null?null:until.toInstant(),rs.getBoolean("cancel_requested"),rs.getInt("input_revision"));
        };
    }
    private RowMapper<Step> stepMapper(){return(rs,n)->new Step(rs.getString("id"),rs.getString("step_key"),rs.getString("parameter_hash"),rs.getString("call_id"),rs.getString("status"),rs.getString("result_json")==null?Map.of():map(rs.getString("result_json")),rs.getLong("active_millis"),rs.getString("target"),rs.getString("request_path"),rs.getString("request_json"),rs.getString("consumption"),rs.getString("tool_name"));}
    private void samePayload(String id,String hash){if(!hash.equals(jdbc.queryForObject("SELECT payload_hash FROM agent_execution WHERE id=?",String.class,id)))throw conflict("幂等请求参数不一致");}
    public String write(Object value){return json.writeValueAsString(value);}
    @SuppressWarnings("unchecked") public Map<String,Object> map(String value){return json.readValue(value,Map.class);}
    public static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Instant now(){return Objects.requireNonNull(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class)).toInstant();}
    private static Timestamp stamp(Instant at){return Timestamp.from(at);}
    private static void authenticated(ChatIdentity i){if(i==null||!i.authenticated())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");}
    private static void assertOwner(ChatIdentity i,Row row){if(!i.tenantId().equals(row.identity().tenantId())||!i.userId().equals(row.identity().userId()))throw missing();}
    private static void safeKey(String id){if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,128}"))throw bad("请求标识无效");}
    private static void uuid(String id){try{if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new IllegalArgumentException();}catch(Exception e){throw bad("任务标识无效");}}
    private static void validate(Create c){
        if(c==null)throw bad("请求不能为空");safeKey(c.requestId());
        if(c.type()==null||!Set.of("CHAT","DOCUMENT_QA","AGENT").contains(c.type().toUpperCase(Locale.ROOT)))throw bad("执行类型无效");
        if(c.question()==null||c.question().isBlank()||c.question().length()>16000)throw bad("问题不能为空或过长");
        if(c.conversationId()!=null)uuid(c.conversationId());if(c.documentConversationId()!=null)uuid(c.documentConversationId());
        if(c.type().equalsIgnoreCase("DOCUMENT_QA")&&c.documentConversationId()==null)throw bad("请选择文档会话");
    }
    private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private static ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在或不属于当前用户");}
}
