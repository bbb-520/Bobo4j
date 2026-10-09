package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigInteger;
import java.util.*;

/** Durable receipts precede settlement; no provider I/O runs while wallet/budget locks are held. */
@Service
public class ModelCallGroupService {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    private final long rate,reserve,groupCap,globalCap; private final boolean enabled;
    private final int unknownCap,agingSeconds;
    public ModelCallGroupService(JdbcTemplate jdbc,TransactionTemplate tx,
            @Value("${app.billing.token-price-per-1k-micros:10000}") long rate,
            @Value("${app.billing.text-reserve-micros:200000}") long reserve,
            @Value("${app.billing.platform-fault.enabled:false}") boolean enabled,
            @Value("${app.billing.platform-fault.group-cap-micros:400000}") long groupCap,
            @Value("${app.billing.platform-fault.window-cap-micros:2000000}") long globalCap,
            @Value("${app.billing.platform-fault.unresolved-cap:10}") int unknownCap,
            @Value("${app.billing.admission-aging-seconds:30}") int agingSeconds) {
        if(rate<=0||reserve<=0||groupCap<0||globalCap<0||unknownCap<0||agingSeconds<=0) throw new IllegalArgumentException("计费组配置无效");
        this.jdbc=jdbc;this.tx=tx;this.rate=rate;this.reserve=reserve;this.enabled=enabled;
        this.groupCap=groupCap;this.globalCap=globalCap;this.unknownCap=unknownCap;this.agingSeconds=agingSeconds;
    }
    public GroupState reserve(String user,String tenant,GroupReserve r) {
        if(r==null||!safe(r.callId(),128)||!safe(r.parameterHash(),64)||!List.of("CHAT","EMBEDDING","RERANK","EVALUATION").contains(r.capability())) throw new BillingException(400,"计费组请求无效");
        // A durable ticket survives a busy wallet; each batch retains its original aging timestamp.
        tx.executeWithoutResult(s -> {
            var w=wallet(user);if(num(w,"balance_micros")<0)throw new BillingException(402,"账户欠费，请充值");var rows=jdbc.queryForList("SELECT * FROM model_call_group WHERE id=?",r.callId());
            if(rows.isEmpty())jdbc.update("INSERT INTO model_call_group(id,user_id,tenant_id,parameter_hash,capability,foreground,status,rate_per_1k_micros) VALUES (?,?,?,?,?,?,'QUEUED',?)",r.callId(),user,tenant,r.parameterHash(),r.capability(),r.foreground(),rate);
            else checkRequest(rows.getFirst(),user,tenant,r);
        });
        return tx.execute(s -> {
            var w=wallet(user);var g=group(user,tenant,r.callId());checkRequest(g,user,tenant,r);
            if(num(w,"balance_micros")<0)throw new BillingException(402,"账户欠费，请充值");
            if(List.of("FAILED_RESOLVED","CANCELLED").contains(g.get("status"))) {
                jdbc.update("INSERT INTO model_call_attempt_history(group_id,generation,role,provider,model,endpoint,credential_id,status,planned_token_upper,reserved_cost_micros,input_tokens,output_tokens,provider_request_id,receipt,cost_micros,cost_owner,created_at,updated_at) SELECT group_id,?,role,provider,model,endpoint,credential_id,status,planned_token_upper,reserved_cost_micros,input_tokens,output_tokens,provider_request_id,receipt,cost_micros,cost_owner,created_at,updated_at FROM model_call_attempt WHERE group_id=?",num(g,"generation"),r.callId());
                jdbc.update("DELETE FROM model_call_attempt WHERE group_id=?",r.callId());
                jdbc.update("UPDATE model_call_group SET status='QUEUED',generation=generation+1,reserved_micros=0,created_at=CURRENT_TIMESTAMP WHERE id=?",r.callId());g=group(user,tenant,r.callId());
            }
            if(!"QUEUED".equals(g.get("status")))return state(g);
            if(w.get("active_usage_id")!=null){var activeGroup=jdbc.queryForList("SELECT status FROM model_call_group WHERE id=?",w.get("active_usage_id"));if(!activeGroup.isEmpty()&&List.of("RESERVED","RUNNING","RECEIVED").contains(activeGroup.getFirst().get("status")))return state(g);throw new BillingException(409,"存在生成中或待对账的消费，请稍后重试");}
            lockBudget();
            jdbc.update("UPDATE model_call_group SET status='CANCELLED' WHERE status='QUEUED' AND created_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP)");
            var first=jdbc.queryForList("SELECT g.id FROM model_call_group g JOIN billing_wallet w ON w.user_id=g.user_id WHERE g.status='QUEUED' AND w.active_usage_id IS NULL AND w.balance_micros>=0 AND w.balance_micros-w.reserved_micros>=GREATEST(0,?-FLOOR(CAST(w.free_tokens AS DECIMAL(65,0))*g.rate_per_1k_micros/1000)) ORDER BY CASE WHEN g.foreground=TRUE OR g.created_at<=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP) THEN 0 ELSE 1 END,g.created_at,g.id LIMIT 1",reserve,-agingSeconds);
            long active=jdbc.queryForObject("SELECT COUNT(*) FROM model_call_group WHERE status IN ('RESERVED','RUNNING','UNKNOWN','RECEIVED')",Long.class);
            if(active>=4||!first.isEmpty()&&!r.callId().equals(first.getFirst().get("id")))return state(g);
            long hold=TokenAllowance.cashHold(reserve,num(w,"free_tokens"),num(g,"rate_per_1k_micros"));
            if(num(w,"balance_micros")-num(w,"reserved_micros")<hold)throw new BillingException(402,"余额不足，请充值后生成");
            jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros+?,active_usage_id=? WHERE user_id=?",hold,r.callId(),user);
            jdbc.update("UPDATE model_call_group SET status='RESERVED',reserved_micros=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",hold,r.callId());
            return state(group(user,tenant,r.callId()));
        });
    }
    public AttemptState attempt(String user,String tenant,String id,AttemptReserve r) {
        if(r==null||r.plannedTokenUpper()<0||!List.of("PRIMARY","FALLBACK").contains(r.role())||!safe(r.provider(),32)||r.model()==null||r.model().isBlank()||r.model().length()>128||r.endpoint()==null||r.endpoint().length()>500||!safe(r.credentialId(),64))throw new BillingException(400,"模型尝试无效");
        return tx.execute(s -> {
            var w=wallet(user);var g=group(user,tenant,id);var old=attemptRows(id,r.role());
            if(num(w,"balance_micros")<0)throw new BillingException(402,"账户欠费，请充值");
            if(!old.isEmpty()) {
                var a=old.getFirst();if(!Objects.equals(a.get("provider"),r.provider())||!Objects.equals(a.get("model"),r.model())||!Objects.equals(a.get("endpoint"),r.endpoint())||!Objects.equals(a.get("credential_id"),r.credentialId())||num(a,"planned_token_upper")!=r.plannedTokenUpper())throw new BillingException(409,"模型尝试参数已变更");
                return new AttemptState(r.role(),(String)a.get("status"),(int)num(g,"generation"));
            }
            if(g.get("receipt")!=null||!List.of("RESERVED","RUNNING","UNKNOWN").contains(g.get("status")))throw new BillingException(409,"计费组状态不允许派发");
            long authorized=Math.max(reserve,cost(r.plannedTokenUpper(),0,num(g,"rate_per_1k_micros")));
            long cashAuthorized=TokenAllowance.cashHold(authorized,num(w,"free_tokens"),num(g,"rate_per_1k_micros"));
            long increase=Math.max(0,cashAuthorized-num(g,"reserved_micros"));
            if(!id.equals(w.get("active_usage_id")))throw new BillingException(409,"计费组钱包占用已变更");
            if(num(w,"balance_micros")-num(w,"reserved_micros")<increase)throw new BillingException(402,"余额不足以授权模型用量上界，请充值后生成");
            if(increase>0){jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros+? WHERE user_id=?",increase,user);jdbc.update("UPDATE model_call_group SET reserved_micros=reserved_micros+? WHERE id=?",increase,id);}
            if("FALLBACK".equals(r.role())) {
                var p=attemptRow(id,"PRIMARY");
                if(!List.of("UNKNOWN","SKIPPED").contains(p.get("status")))throw new BillingException(409,"主调用未进入故障对账状态");
                if(Objects.equals(p.get("provider"),r.provider())&&Objects.equals(p.get("model"),r.model())&&Objects.equals(p.get("endpoint"),r.endpoint()))throw new BillingException(400,"备用模型必须为不同配置");
                var b=lockBudget();int pending="UNKNOWN".equals(p.get("status"))?2:1;long risk=Math.addExact(authorized,pending==2?num(p,"reserved_cost_micros"):0);
                if(!enabled||risk>groupCap||num(b,"allocated_micros")+num(b,"window_spent_micros")+risk>globalCap||num(b,"unresolved")+pending>unknownCap)throw new BillingException(409,"故障成本政策未启用或额度不足，消费等待对账");
                jdbc.update("UPDATE model_fault_budget SET allocated_micros=allocated_micros+?,unresolved=unresolved+? WHERE id=1",risk,pending);
                jdbc.update("UPDATE model_call_group SET risk_micros=?,risk_attempts=? WHERE id=?",risk,pending,id);
                jdbc.update("UPDATE model_call_attempt SET cost_owner='PLATFORM' WHERE group_id=? AND role='PRIMARY'",id);
            }
            jdbc.update("INSERT INTO model_call_attempt(group_id,role,provider,model,endpoint,credential_id,status,planned_token_upper,reserved_cost_micros) VALUES (?,?,?,?,?,?,'RESERVED',?,?)",id,r.role(),r.provider(),r.model(),r.endpoint(),r.credentialId(),r.plannedTokenUpper(),authorized);
            return new AttemptState(r.role(),"RESERVED",(int)num(g,"generation"));
        });
    }
    public void dispatch(String user,String tenant,String id,String role) {
        dispatch(user,tenant,id,role,1);
    }
    public void dispatch(String user,String tenant,String id,String role,int generation) {
        tx.executeWithoutResult(s -> {if(num(wallet(user),"balance_micros")<0)throw new BillingException(402,"账户欠费，请充值");requireGeneration(group(user,tenant,id),generation);
            if(jdbc.update("UPDATE model_call_attempt SET status='DISPATCHED',updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND role=? AND status='RESERVED'",id,role)!=1)throw new BillingException(409,"调用已派发，禁止重复调用模型");
            jdbc.update("UPDATE model_call_group SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND receipt IS NULL",id);
        });
    }
    public void unknown(String user,String tenant,String id,String role) {
        unknown(user,tenant,id,role,1);
    }
    public void unknown(String user,String tenant,String id,String role,int generation) {
        tx.executeWithoutResult(s -> {wallet(user);requireGeneration(group(user,tenant,id),generation);
            int changed=jdbc.update("UPDATE model_call_attempt SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND role=? AND status='DISPATCHED'",id,role);
            if(changed>0)jdbc.update("UPDATE model_call_group SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE id=? AND receipt IS NULL",id);
        });
    }
    public void skip(String user,String tenant,String id,String role,int generation) {tx.executeWithoutResult(s -> {wallet(user);requireGeneration(group(user,tenant,id),generation);if(jdbc.update("UPDATE model_call_attempt SET status='SKIPPED',updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND role=? AND status='RESERVED'",id,role)!=1)throw new BillingException(409,"已派发模型不能标记未调用");});}
    public void receipt(String user,String tenant,String id,AttemptReceipt r) {
        if(r==null||r.inputTokens()==null||r.outputTokens()==null||r.inputTokens()<0||r.outputTokens()<0||r.resultJson()==null||r.resultJson().length()>16_000_000||r.providerRequestId()!=null&&r.providerRequestId().length()>128)throw new BillingException(400,"缺少真实用量或回执无效");
        tx.executeWithoutResult(s -> {
            wallet(user);var g=group(user,tenant,id);requireGeneration(g,r.generation());var a=attemptRow(id,r.role());long cost=cost(r.inputTokens(),r.outputTokens(),num(g,"rate_per_1k_micros"));
            if(a.get("receipt")!=null) {
                if(!Objects.equals(a.get("receipt"),r.resultJson())||num(a,"input_tokens")!=r.inputTokens()||num(a,"output_tokens")!=r.outputTokens()||!Objects.equals(a.get("provider_request_id"),r.providerRequestId()))throw new BillingException(409,"回执幂等参数不一致");return;
            }
            if(!List.of("DISPATCHED","UNKNOWN").contains(a.get("status")))throw new BillingException(409,"未派发调用不能写回执");
            boolean late="PRIMARY".equals(r.role())&&!attemptRows(id,"FALLBACK").isEmpty()||g.get("receipt")!=null;
            jdbc.update("UPDATE model_call_attempt SET status='RECEIVED',receipt=?,input_tokens=?,output_tokens=?,provider_request_id=?,cost_micros=?,updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND role=?",r.resultJson(),r.inputTokens(),r.outputTokens(),r.providerRequestId(),cost,id,r.role());
            if(!late)jdbc.update("UPDATE model_call_group SET status='RECEIVED',receipt=?,winning_role=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND receipt IS NULL",r.resultJson(),r.role(),id);
        });
    }
    public GroupState finish(String user,String tenant,String id) {
        return tx.execute(s -> {
            var w=wallet(user);var g=group(user,tenant,id);
            if("SUCCEEDED".equals(g.get("status")))return state(g);
            if(g.get("receipt")==null)throw new BillingException(409,"模型结果未知，等待对账");
            var a=attemptRow(id,(String)g.get("winning_role"));long gross=num(a,"cost_micros");
            long total=TokenAllowance.total(num(a,"input_tokens"),num(a,"output_tokens"));
            long freeUsed=Math.min(total,num(w,"free_tokens"));
            long amount=cost(total-freeUsed,0,num(g,"rate_per_1k_micros"));
            if(gross>num(a,"reserved_cost_micros"))throw new BillingException(409,"实际用量超出授权额度，等待对账");
            if(amount>num(g,"reserved_micros"))throw new BillingException(409,"实际用量超出授权额度，等待对账");
            if(!id.equals(w.get("active_usage_id")))throw new BillingException(409,"计费组占用已变更");
            jdbc.update("UPDATE billing_wallet SET balance_micros=balance_micros-?,free_tokens=free_tokens-?,reserved_micros=reserved_micros-?,active_usage_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND active_usage_id=?",amount,freeUsed,num(g,"reserved_micros"),user,id);
            jdbc.update("UPDATE model_call_attempt SET status='SUCCEEDED' WHERE group_id=? AND role=?",id,g.get("winning_role"));
            jdbc.update("UPDATE model_call_group SET status='SUCCEEDED',charged_micros=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",amount,id);
            if(num(g,"risk_micros")>0) {
                long released=num(a,"reserved_cost_micros");lockBudget();jdbc.update("UPDATE model_fault_budget SET allocated_micros=allocated_micros-?,unresolved=unresolved-1 WHERE id=1",released);
                jdbc.update("UPDATE model_call_group SET risk_micros=risk_micros-?,risk_attempts=risk_attempts-1 WHERE id=?",released,id);
            }
            jdbc.update("INSERT INTO model_usage(id,user_id,capability,model,status,trial,reserved_micros,rate_per_1k_micros,image_price_micros,input_tokens,output_tokens,metering,charged_micros,free_tokens_used,provider_request_id) VALUES (?,?,?,?,'SUCCEEDED',?,0,?,0,?,?,'TOKEN',?,?,?)",id,user,g.get("capability"),a.get("model"),freeUsed>0,g.get("rate_per_1k_micros"),a.get("input_tokens"),a.get("output_tokens"),amount,freeUsed,a.get("provider_request_id"));
            return state(group(user,tenant,id));
        });
    }
    public GroupState state(String user,String tenant,String id) {return state(group(user,tenant,id));}
    public String capability(String user,String tenant,String id) {return (String)group(user,tenant,id).get("capability");}
    public void cancel(String user,String tenant,String id,int generation) {tx.executeWithoutResult(s -> {var w=wallet(user);var g=group(user,tenant,id);requireGeneration(g,generation);if("CANCELLED".equals(g.get("status")))return;long dispatched=jdbc.queryForObject("SELECT COUNT(*) FROM model_call_attempt WHERE group_id=? AND status IN ('DISPATCHED','UNKNOWN','RECEIVED','SUCCEEDED')",Long.class,id);if(dispatched>0||g.get("receipt")!=null)throw new BillingException(409,"已派发调用不能取消计费占用");if(id.equals(w.get("active_usage_id")))jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros-?,active_usage_id=NULL WHERE user_id=? AND active_usage_id=?",num(g,"reserved_micros"),user,id);jdbc.update("UPDATE model_call_group SET status='CANCELLED',reserved_micros=0,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);jdbc.update("UPDATE model_call_attempt SET status='CANCELLED' WHERE group_id=? AND status='RESERVED'",id);});}
    public void resolveFailure(String user,String tenant,String id,long cost,String actor,String reason) {
        resolveFailure(user,tenant,id,cost,actor,reason,false);
    }
    public void resolveFailure(String user,String tenant,String id,long cost,String actor,String reason,boolean noUsageConfirmed) {
        if(cost<0||reason==null||reason.isBlank()||reason.length()>500)throw new BillingException(400,"必须填写供应商对账依据");
        tx.executeWithoutResult(s -> {
            var w=wallet(user);var g=group(user,tenant,id);var b=lockBudget();
            if("FAILED_RESOLVED".equals(g.get("status")))return;
            if(g.get("receipt")!=null||!List.of("UNKNOWN","RUNNING").contains(g.get("status")))throw new BillingException(409,"此计费组不能确认为失败");
            long risk=num(g,"risk_micros");
            if(cost>0&&(!enabled||cost>groupCap||num(b,"allocated_micros")-risk+num(b,"window_spent_micros")+cost>globalCap))throw new BillingException(409,"平台故障费用没有授权额度");
            if(!id.equals(w.get("active_usage_id")))throw new BillingException(409,"计费组占用已变更");
            if(noUsageConfirmed&&cost!=0)throw new BillingException(400,"无供应商执行用量与非零费用冲突");
            jdbc.update("INSERT INTO model_call_reconciliation(group_id,generation,role,actor,reason,cost_micros,no_usage_confirmed) VALUES (?,?,'GROUP',?,?,?,?)",id,num(g,"generation"),actor,reason,cost,noUsageConfirmed);
            jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros-?,active_usage_id=NULL WHERE user_id=? AND active_usage_id=?",num(g,"reserved_micros"),user,id);
            if(risk>0)jdbc.update("UPDATE model_fault_budget SET allocated_micros=allocated_micros-?,unresolved=unresolved-?,window_spent_micros=window_spent_micros+? WHERE id=1",risk,num(g,"risk_attempts"),cost);
            else if(cost>0)jdbc.update("UPDATE model_fault_budget SET window_spent_micros=window_spent_micros+? WHERE id=1",cost);
            jdbc.update("UPDATE model_call_group SET status='FAILED_RESOLVED',risk_micros=0,risk_attempts=0,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
            jdbc.update("UPDATE model_call_attempt SET status='FAILED',cost_owner='PLATFORM',updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND status IN ('UNKNOWN','DISPATCHED')",id);
            if(noUsageConfirmed)jdbc.update("UPDATE model_call_attempt SET input_tokens=0,output_tokens=0 WHERE group_id=? AND status='FAILED' AND input_tokens IS NULL AND output_tokens IS NULL",id);
        });
    }
    /** Explicit operator evidence closes unknown platform exposure, including late measured receipts. */
    public void reconcile(String user,String tenant,String id,String role,long actualCost,String actor,String reason) {
        reconcile(user,tenant,id,role,actualCost,actor,reason,false);
    }
    public void reconcile(String user,String tenant,String id,String role,long actualCost,String actor,String reason,boolean noUsageConfirmed) {
        if(actualCost<0||reason==null||reason.isBlank()||reason.length()>500)throw new BillingException(400,"必须填写供应商对账依据");
        tx.executeWithoutResult(s -> {wallet(user);var g=group(user,tenant,id);var a=attemptRow(id,role);lockBudget();
            if(!jdbc.queryForList("SELECT group_id FROM model_call_reconciliation WHERE group_id=? AND generation=? AND role=?",id,num(g,"generation"),role).isEmpty())return;
            long authorized=num(a,"reserved_cost_micros");
            if(!"PLATFORM".equals(a.get("cost_owner"))||num(g,"risk_micros")<authorized)throw new BillingException(409,"此尝试尚未进入平台对账");
            if(actualCost>authorized)throw new BillingException(409,"供应商故障费用超出授权上限，需人工审计");
            if(noUsageConfirmed&&actualCost!=0)throw new BillingException(400,"无供应商执行用量与非零费用冲突");
            jdbc.update("INSERT INTO model_call_reconciliation(group_id,generation,role,actor,reason,cost_micros,no_usage_confirmed) VALUES (?,?,?,?,?,?,?)",id,num(g,"generation"),role,actor,reason,actualCost,noUsageConfirmed);
            jdbc.update("UPDATE model_fault_budget SET allocated_micros=allocated_micros-?,unresolved=unresolved-1,window_spent_micros=window_spent_micros+? WHERE id=1",authorized,actualCost);
            jdbc.update("UPDATE model_call_group SET risk_micros=risk_micros-?,risk_attempts=risk_attempts-1 WHERE id=?",authorized,id);
            jdbc.update("UPDATE model_call_attempt SET status='RECONCILED',cost_micros=? WHERE group_id=? AND role=?",actualCost,id,role);
            if(noUsageConfirmed&&a.get("input_tokens")==null&&a.get("output_tokens")==null)jdbc.update("UPDATE model_call_attempt SET input_tokens=0,output_tokens=0 WHERE group_id=? AND role=?",id,role);
        });
    }
    private Map<String,Object> wallet(String user) {var rows=jdbc.queryForList("SELECT * FROM billing_wallet WHERE user_id=? FOR UPDATE",user);if(rows.isEmpty())throw new BillingException(404,"账户不存在");return rows.getFirst();}
    /** Late supplier token evidence may settle an archived generation without altering money or its winner. */
    public void reconcileTokens(String user,String tenant,String id,int generation,String role,long input,long output,String actor,String reason) {
        if(generation<1||!List.of("PRIMARY","FALLBACK").contains(role)||input<0||output<0||reason==null||reason.isBlank()||reason.length()>500)throw new BillingException(400,"真实用量对账证据无效");
        tx.executeWithoutResult(s -> {
            wallet(user);var g=group(user,tenant,id);if(generation>num(g,"generation"))throw new BillingException(409,"调用版本不存在");
            boolean current=generation==num(g,"generation");String table=current?"model_call_attempt":"model_call_attempt_history";
            var rows=current?jdbc.queryForList("SELECT * FROM model_call_attempt WHERE group_id=? AND role=?",id,role):jdbc.queryForList("SELECT * FROM model_call_attempt_history WHERE group_id=? AND generation=? AND role=?",id,generation,role);
            if(rows.isEmpty())throw new BillingException(404,"模型尝试不存在");var a=rows.getFirst();
            if(a.get("input_tokens")!=null||a.get("output_tokens")!=null){if(num(a,"input_tokens")!=input||num(a,"output_tokens")!=output)throw new BillingException(409,"已确认用量不能改写");return;}
            if(!List.of("DISPATCHED","UNKNOWN","FAILED","RECONCILED").contains(a.get("status")))throw new BillingException(409,"未派发尝试不能写入供应商用量");
            jdbc.update("INSERT INTO model_call_token_reconciliation(group_id,generation,role,input_tokens,output_tokens,actor,reason) VALUES (?,?,?,?,?,?,?)",id,generation,role,input,output,actor,reason);
            if(current)jdbc.update("UPDATE "+table+" SET input_tokens=?,output_tokens=?,updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND role=?",input,output,id,role);
            else jdbc.update("UPDATE "+table+" SET input_tokens=?,output_tokens=?,updated_at=CURRENT_TIMESTAMP WHERE group_id=? AND generation=? AND role=?",input,output,id,generation,role);
        });
    }
    private Map<String,Object> group(String user,String tenant,String id) {var rows=jdbc.queryForList("SELECT * FROM model_call_group WHERE id=?",id);if(rows.isEmpty())throw new BillingException(404,"计费组不存在");var g=rows.getFirst();if(!Objects.equals(user,g.get("user_id"))||!Objects.equals(tenant,g.get("tenant_id")))throw new BillingException(404,"计费组不存在");return g;}
    private Map<String,Object> lockBudget() {jdbc.queryForList("SELECT * FROM model_fault_budget WHERE id=1 FOR UPDATE");jdbc.update("UPDATE model_fault_budget SET window_spent_micros=0,window_started=CURRENT_TIMESTAMP WHERE id=1 AND window_started<TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP)");return jdbc.queryForList("SELECT * FROM model_fault_budget WHERE id=1").getFirst();}
    private List<Map<String,Object>> attemptRows(String id,String role) {return jdbc.queryForList("SELECT * FROM model_call_attempt WHERE group_id=? AND role=?",id,role);}
    private Map<String,Object> attemptRow(String id,String role) {var rows=attemptRows(id,role);if(rows.isEmpty())throw new BillingException(409,"模型尝试不存在");return rows.getFirst();}
    private GroupState state(Map<String,Object> g) {long input=0,output=0,unknown=0;String id=(String)g.get("id");var attempts=jdbc.queryForList("SELECT status,planned_token_upper,input_tokens,output_tokens FROM model_call_attempt WHERE group_id=? UNION ALL SELECT status,planned_token_upper,input_tokens,output_tokens FROM model_call_attempt_history WHERE group_id=?",id,id);for(var a:attempts){if(a.get("input_tokens")!=null&&a.get("output_tokens")!=null){input=Math.addExact(input,num(a,"input_tokens"));output=Math.addExact(output,num(a,"output_tokens"));}else if(List.of("DISPATCHED","UNKNOWN","FAILED","RECONCILED").contains(a.get("status")))unknown=Math.addExact(unknown,num(a,"planned_token_upper"));}return new GroupState(id,(String)g.get("status"),(String)g.get("receipt"),(String)g.get("winning_role"),(int)num(g,"generation"),input,output,unknown);}
    private static void requireGeneration(Map<String,Object> g,int generation){if(num(g,"generation")!=generation)throw new BillingException(409,"旧模型尝试版本不能提交");}
    private static void checkRequest(Map<String,Object> g,String user,String tenant,GroupReserve r) {if(!Objects.equals(user,g.get("user_id"))||!Objects.equals(tenant,g.get("tenant_id")))throw new BillingException(404,"计费组不存在");if(!Objects.equals(r.parameterHash(),g.get("parameter_hash"))||!Objects.equals(r.capability(),g.get("capability"))||!Objects.equals(r.foreground(),g.get("foreground")))throw new BillingException(409,"幂等键对应的请求已变更");}
    private static boolean safe(String value,int max) {return value!=null&&value.matches("[A-Za-z0-9_.:-]{1,"+max+"}");}
    private static long num(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static long cost(long input,long output,long rate) {try{return BigInteger.valueOf(input).add(BigInteger.valueOf(output)).multiply(BigInteger.valueOf(rate)).add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000)).longValueExact();}catch(ArithmeticException e){throw new BillingException(400,"模型用量超出计费范围");}}
}
