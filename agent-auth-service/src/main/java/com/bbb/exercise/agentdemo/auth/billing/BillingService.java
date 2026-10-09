package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.api.billing.BillingContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Short database transactions serialize each wallet; no provider I/O runs under these locks. */
@Service
public class BillingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final long rate, reserve, imagePrice, warning;
    public BillingService(JdbcTemplate jdbc, TransactionTemplate tx,
                          @Value("${app.billing.token-price-per-1k-micros:10000}") long rate,
                          @Value("${app.billing.text-reserve-micros:200000}") long reserve,
                          @Value("${app.billing.image-price-micros:1000000}") long imagePrice,
                          @Value("${app.billing.low-balance-micros:500000}") long warning) {
        if(rate<=0||reserve<=0||imagePrice<=0||warning<0) throw new IllegalArgumentException("计费配置无效");
        this.jdbc=jdbc; this.tx=tx; this.rate=rate; this.reserve=reserve; this.imagePrice=imagePrice; this.warning=warning;
    }
    public void createWallet(String user, int freeImages) {
        createWallet(user, freeImages, 0);
    }
    public void createWallet(String user, int freeImages, long freeTokens) {
        if (freeImages < 0 || freeTokens < 0) throw new IllegalArgumentException("免费额度无效");
        jdbc.update("INSERT INTO billing_wallet(user_id,free_images,free_tokens) VALUES (?,?,?)",user,freeImages,freeTokens);
    }
    public Wallet wallet(String user) { return walletRow(user,false); }
    private Wallet walletRow(String user,boolean lock) {
        var rows=jdbc.query("SELECT * FROM billing_wallet WHERE user_id=?"+(lock?" FOR UPDATE":""),(rs,n)-> {
            long balance=rs.getLong("balance_micros"), held=rs.getLong("reserved_micros"); int free=rs.getInt("free_images");
            String active=rs.getString("active_usage_id");
            long freeTokens=rs.getLong("free_tokens");
            boolean debt=balance<0;
            boolean low=debt || (freeTokens>0 ? freeTokens<=100_000 : balance-held<=warning);
            return new Wallet(balance,held,free,debt,low,active!=null || debt,active,freeTokens);
        },user);
        if(rows.isEmpty()) throw new BillingException(404,"账户不存在"); return rows.getFirst();
    }
    public Reservation reserve(String user,Reserve request) {
        validate(request);
        return tx.execute(s -> {
            Wallet w=walletRow(user,true);
            var rows=jdbc.queryForList("SELECT * FROM model_usage WHERE id=?",request.requestId());
            if(!rows.isEmpty()) {
                var old=rows.getFirst(); assertOwner(old,user);
                if(!request.capability().equals(old.get("capability"))||!request.model().equals(old.get("model"))) throw new BillingException(409,"幂等键对应的请求已变更");
                if(!"RESERVED".equals(old.get("status"))) throw new BillingException(409,"该调用已派发或已结束，禁止重复生成");
                return reservation(old);
            }
            if(w.activeUsageId()!=null) throw new BillingException(409,"存在生成中或待对账的消费，请稍后重试");
            // A negative wallet cannot use remaining promotions to bypass debt.
            if(w.balanceMicros()<0) throw new BillingException(402,"账户欠费，请充值");
            boolean trial="IMAGE".equals(request.capability()) && w.freeImages()>0;
            boolean freeTokenCall=!"IMAGE".equals(request.capability()) && w.freeTokens()>0;
            long hold=trial||freeTokenCall?0:("IMAGE".equals(request.capability())?Math.max(imagePrice,reserve):reserve);
            if(!trial && !freeTokenCall && (w.balanceMicros()<=0||w.balanceMicros()-w.reservedMicros()<hold)) throw new BillingException(402,"余额不足，请充值后生成");
            jdbc.update("INSERT INTO model_usage(id,user_id,capability,model,status,trial,reserved_micros,rate_per_1k_micros,image_price_micros) VALUES (?,?,?,?,'RESERVED',?,?,?,?)",
                    request.requestId(),user,request.capability(),request.model(),trial,hold,rate,imagePrice);
            jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros+?,free_images=free_images-?,active_usage_id=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                    hold,trial?1:0,request.requestId(),user);
            return new Reservation(request.requestId(),trial,hold,"RESERVED");
        });
    }
    public void dispatch(String user,String id) {
        tx.executeWithoutResult(s -> { if(walletRow(user,true).arrears()) throw new BillingException(402,"账户欠费，请充值"); usageRow(user,id);
            if(jdbc.update("UPDATE model_usage SET status='DISPATCHED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='RESERVED'",id,user)!=1)
                throw new BillingException(409,"调用已派发，禁止重复调用模型");
        });
    }
    public Wallet settle(String user,String id,Settlement result) {
        if(result==null || result.inputTokens()!=null && result.inputTokens()<0 || result.outputTokens()!=null && result.outputTokens()<0
                || result.providerRequestId()!=null && result.providerRequestId().length()>128) throw new BillingException(400,"模型用量无效");
        return tx.execute(s -> {
            Wallet w=walletRow(user,true); var u=usageRow(user,id); String status=(String)u.get("status");
            if("SUCCEEDED".equals(status)) {
                if(!Objects.equals(numberOrNull(u.get("input_tokens")),result.inputTokens())||!Objects.equals(numberOrNull(u.get("output_tokens")),result.outputTokens())
                        || !Objects.equals(u.get("provider_request_id"),result.providerRequestId())) throw new BillingException(409,"结算幂等键参数不一致");
                return wallet(user);
            }
            if(!"DISPATCHED".equals(status) && !"UNKNOWN".equals(status)) throw new BillingException(409,"消费状态不允许结算");
            boolean tokens=result.inputTokens()!=null && result.outputTokens()!=null;
            if(!tokens && !"IMAGE".equals(u.get("capability"))) throw new BillingException(409,"缺少真实 token 用量，需要对账");
            boolean imageTrial=Boolean.TRUE.equals(u.get("trial"));
            long total=tokens?TokenAllowance.total(result.inputTokens(),result.outputTokens()):0;
            long freeUsed=tokens&&!imageTrial?Math.min(total,w.freeTokens()):0;
            long cost=imageTrial?0:tokens?tokenCost(total-freeUsed,0,num(u,"rate_per_1k_micros")):num(u,"image_price_micros");
            jdbc.update("UPDATE model_usage SET status='SUCCEEDED',input_tokens=?,output_tokens=?,metering=?,charged_micros=?,trial=?,free_tokens_used=?,provider_request_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
                    result.inputTokens(),result.outputTokens(),tokens?"TOKEN":"IMAGE",cost,imageTrial||freeUsed>0,freeUsed,result.providerRequestId(),id,user);
            jdbc.update("UPDATE billing_wallet SET balance_micros=balance_micros-?,free_tokens=free_tokens-?,reserved_micros=reserved_micros-?,active_usage_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND active_usage_id=?",
                    cost,freeUsed,num(u,"reserved_micros"),user,id);
            return wallet(user);
        });
    }
    public void unknown(String user,String id) {
        tx.executeWithoutResult(s -> { walletRow(user,true); var u=usageRow(user,id);
            if("RESERVED".equals(u.get("status"))) {cancel(user,id);return;}
            jdbc.update("UPDATE model_usage SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='DISPATCHED'",id,user);
        });
    }
    public String status(String user,String id) {return (String)usageRow(user,id).get("status");}
    public Wallet reconcileRefund(String user,String id,String actor,String reason) {
        if(reason==null||reason.isBlank()||reason.length()>500) throw new BillingException(400,"必须填写供应商对账依据（最多500字）");
        return tx.execute(s -> {
            walletRow(user,true);var row=usageRow(user,id);String state=(String)row.get("status");
            if("REFUNDED".equals(state))return wallet(user);
            if(!List.of("DISPATCHED","UNKNOWN","SUCCEEDED").contains(state))throw new BillingException(409,"该消费状态不允许对账退款");
            boolean complete="SUCCEEDED".equals(state);long hold=complete?0:num(row,"reserved_micros");
            long freeUsed=complete?num(row,"free_tokens_used"):0;
            boolean trial=Boolean.TRUE.equals(row.get("trial")) && freeUsed==0;long paid=complete?num(row,"charged_micros"):0;
            jdbc.update("UPDATE billing_wallet SET balance_micros=balance_micros+?,free_tokens=free_tokens+?,reserved_micros=reserved_micros-?,free_images=free_images+?,active_usage_id=CASE WHEN active_usage_id=? THEN NULL ELSE active_usage_id END,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",paid,freeUsed,hold,trial?1:0,id,user);
            jdbc.update("UPDATE model_usage SET status='REFUNDED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",id,user);
            jdbc.update("INSERT INTO usage_reconciliation(usage_id,action,actor,reason) VALUES (?,'REFUND',?,?)",id,actor,reason);return wallet(user);
        });
    }
    public Wallet reconcileSettle(String user,String id,Settlement result,String actor,String reason) {
        if(reason==null||reason.isBlank()||reason.length()>500) throw new BillingException(400,"必须填写供应商对账依据");
        return tx.execute(s -> {Wallet wallet=settle(user,id,result);
            var rows=jdbc.queryForList("SELECT usage_id FROM usage_reconciliation WHERE usage_id=? AND action='SETTLE'",id);
            if(rows.isEmpty())jdbc.update("INSERT INTO usage_reconciliation(usage_id,action,actor,reason) VALUES (?,'SETTLE',?,?)",id,actor,reason);return wallet;
        });
    }
    /** Only undispatched reservations can be safely released after a crashed caller. */
    public void expireReservations() {
        var rows=jdbc.queryForList("SELECT id,user_id FROM model_usage WHERE status='RESERVED' AND created_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP) LIMIT 100");
        for(var row:rows) {
            tx.executeWithoutResult(s -> {String user=(String)row.get("user_id"),id=(String)row.get("id");walletRow(user,true);
                if("RESERVED".equals(status(user,id))) cancel(user,id);
            });
        }
    }
    public void cancel(String user,String id) {
        tx.executeWithoutResult(s -> {walletRow(user,true); var u=usageRow(user,id);
            if("CANCELLED".equals(u.get("status"))) return;
            if(!"RESERVED".equals(u.get("status"))) throw new BillingException(409,"已派发调用不能直接退款，需核实供应商结果");
            jdbc.update("UPDATE model_usage SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",id,user);
            jdbc.update("UPDATE billing_wallet SET reserved_micros=reserved_micros-?,free_images=free_images+?,active_usage_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND active_usage_id=?",
                    num(u,"reserved_micros"),Boolean.TRUE.equals(u.get("trial"))?1:0,user,id);
        });
    }
    public List<Usage> usage(String user,int limit) {
        return jdbc.query("SELECT * FROM model_usage WHERE user_id=? ORDER BY created_at DESC LIMIT ?",(rs,n)->new Usage(rs.getString("id"),rs.getString("capability"),rs.getString("model"),rs.getString("status"),
                numberOrNull(rs.getObject("input_tokens")),numberOrNull(rs.getObject("output_tokens")),rs.getString("metering"),rs.getLong("charged_micros"),rs.getBoolean("trial"),rs.getString("provider_request_id"),rs.getTimestamp("created_at").toLocalDateTime()),user,Math.max(1,Math.min(100,limit)));
    }
    private Map<String,Object> usageRow(String user,String id) {
        var rows=jdbc.queryForList("SELECT * FROM model_usage WHERE id=?",id);
        if(rows.isEmpty()) throw new BillingException(404,"消费记录不存在"); var row=rows.getFirst(); assertOwner(row,user); return row;
    }
    private static void assertOwner(Map<String,Object> row,String user) { if(!user.equals(row.get("user_id"))) throw new BillingException(404,"消费记录不存在"); }
    private static Reservation reservation(Map<String,Object> row) {return new Reservation((String)row.get("id"),Boolean.TRUE.equals(row.get("trial")),num(row,"reserved_micros"),(String)row.get("status"));}
    private static long num(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static Long numberOrNull(Object value) {return value==null?null:((Number)value).longValue();}
    private static long tokenCost(long input,long output,long rate) {
        try {return BigInteger.valueOf(input).add(BigInteger.valueOf(output)).multiply(BigInteger.valueOf(rate)).add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000)).longValueExact();}
        catch(ArithmeticException e) {throw new BillingException(400,"用量超出计费范围");}
    }
    private static void validate(Reserve r) {
        if(r==null||r.requestId()==null||!r.requestId().matches("[A-Za-z0-9_.:-]{1,128}")||!List.of("IMAGE","CHAT","VISION","EMBEDDING","RERANK","EVALUATION").contains(r.capability())
                ||r.model()==null||r.model().isBlank()||r.model().length()>128) throw new BillingException(400,"计费请求无效");
    }
}
