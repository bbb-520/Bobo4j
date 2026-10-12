package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable full-refund intent: debit wallet before external I/O, retain funds while outcome is unknown. */
@Service
public class PaymentRefundService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public PaymentRefundService(JdbcTemplate db, TransactionTemplate tx) { this.db = db; this.tx = tx; }

    public Refund begin(String orderId, String actor, String reason) {
        if (actor == null || actor.isBlank() || actor.length()>64 || reason == null || reason.isBlank() || reason.length() > 256)
            throw new BillingException(400, "退款必须提供操作者及 1～256 字的原因");
        return tx.execute(s -> {
            var orders = db.queryForList("SELECT * FROM payment_order WHERE id=? FOR UPDATE", orderId);
            if (orders.isEmpty()) throw new BillingException(404, "订单不存在");
            var order = orders.getFirst();
            var existing = db.query("SELECT * FROM payment_refund WHERE order_id=?", (rs,n) -> map(rs), orderId);
            if (!existing.isEmpty()) return existing.getFirst();
            if (!"ALIPAY".equals(order.get("channel")) || !"PAID".equals(order.get("status")))
                throw new BillingException(409, "仅已到账的支付宝订单支持原路退款");
            long cents = ((Number)order.get("amount_cents")).longValue();
            long micros = Math.multiplyExact(cents, 10000L);
            String user = (String)order.get("user_id");
            var wallet = db.queryForMap("SELECT balance_micros,reserved_micros FROM billing_wallet WHERE user_id=? FOR UPDATE", user);
            long available = ((Number)wallet.get("balance_micros")).longValue() - ((Number)wallet.get("reserved_micros")).longValue();
            if (available < micros) throw new BillingException(409, "可用余额不足，已消费或预留的资金不能原路退回");
            String id = UUID.randomUUID().toString().replace("-", "");
            db.update("INSERT INTO payment_refund(id,order_id,amount_cents,actor,reason) VALUES(?,?,?,?,?)", id,orderId,cents,actor,reason.trim());
            db.update("UPDATE billing_wallet SET balance_micros=balance_micros-?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", micros,user);
            PaymentService.ledger(db,orderId,id,"REFUND_HOLD",-micros,actor);
            db.update("UPDATE payment_order SET status='REFUNDING',state_version=state_version+1,qr_code=NULL WHERE id=?", orderId);
            return get(id);
        });
    }

    public Refund get(String id) {
        var rows = db.query("SELECT * FROM payment_refund WHERE id=?", (rs,n) -> map(rs), id);
        if (rows.isEmpty()) throw new BillingException(404, "退款请求不存在");
        return rows.getFirst();
    }
    public Refund forOrder(String orderId) {
        var rows = db.query("SELECT * FROM payment_refund WHERE order_id=?", (rs,n) -> map(rs),orderId);
        if (rows.isEmpty()) throw new BillingException(404,"此订单没有原路退款请求");
        return rows.getFirst();
    }
    public List<Refund> dueRefunds() {
        return db.query("SELECT * FROM payment_refund WHERE status='PENDING' AND next_check_at<=CURRENT_TIMESTAMP ORDER BY next_check_at LIMIT 20", (rs,n) -> map(rs));
    }
    public String acquire(String id) {
        String owner = UUID.randomUUID().toString();
        int n = db.update("UPDATE payment_refund SET sync_owner=?,sync_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP),checks=checks+1 WHERE id=? AND status='PENDING' AND next_check_at<=CURRENT_TIMESTAMP AND (sync_until IS NULL OR sync_until<CURRENT_TIMESTAMP)",owner,id);
        return n == 1 ? owner : null;
    }

    public boolean budgetExhausted(String id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT checks>12 OR created_at<=TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP) FROM payment_refund WHERE id=?",Boolean.class,id));
    }

    public void review(String id,String owner,String reason) {
        tx.executeWithoutResult(s -> {
            Refund snapshot=get(id);
            db.queryForObject("SELECT id FROM payment_order WHERE id=? FOR UPDATE",String.class,snapshot.orderId());
            int n=db.update("UPDATE payment_refund SET status='REVIEW',last_error=?,sync_owner=NULL,sync_until=NULL WHERE id=? AND status='PENDING' AND sync_owner=? AND sync_until>CURRENT_TIMESTAMP",reason,id,owner);
            if(n==1) db.update("UPDATE payment_order SET status='REFUND_REVIEW',state_version=state_version+1,last_error=? WHERE id=? AND status='REFUNDING'",reason,snapshot.orderId());
        });
    }
    public int attempt(String id, String owner) {
        int n = db.update("UPDATE payment_refund SET attempts=attempts+1 WHERE id=? AND status='PENDING' AND sync_owner=? AND sync_until>CURRENT_TIMESTAMP",id,owner);
        if (n != 1) throw new BillingException(409, "退款恢复租约已过期");
        return db.queryForObject("SELECT attempts FROM payment_refund WHERE id=?", Integer.class,id);
    }

    public void succeed(String id, String owner) { complete(id,owner,true); }
    public void rejectFirstAttempt(String id, String owner) { complete(id,owner,false); }
    private void complete(String id, String owner, boolean success) {
        tx.executeWithoutResult(s -> {
            // Consistent lock order: payment_order -> payment_refund -> billing_wallet.
            Refund snapshot = get(id);
            var order = db.queryForMap("SELECT user_id FROM payment_order WHERE id=? FOR UPDATE",snapshot.orderId());
            var rows = db.queryForList("SELECT * FROM payment_refund WHERE id=? FOR UPDATE",id);
            var r = rows.getFirst();
            // JDBC getBoolean handles both MySQL numeric comparison results and H2 BOOLEAN.
            boolean leaseValid = Boolean.TRUE.equals(db.queryForObject("SELECT sync_until>CURRENT_TIMESTAMP FROM payment_refund WHERE id=?",Boolean.class,id));
            if (!"PENDING".equals(r.get("status")) || !owner.equals(r.get("sync_owner")) || !leaseValid) return;
            if (!success && ((Number)r.get("attempts")).intValue() != 1) return;
            if (!success) {
                String user = (String)order.get("user_id");
                db.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id=? FOR UPDATE",Long.class,user);
                db.update("UPDATE billing_wallet SET balance_micros=balance_micros+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",Math.multiplyExact(snapshot.amountCents(),10000L),user);
            }
            PaymentService.ledger(db,snapshot.orderId(),id,success?"REFUND_SUCCEEDED":"REFUND_RELEASE",success?0:Math.multiplyExact(snapshot.amountCents(),10000L),"ALIPAY");
            db.update("UPDATE payment_refund SET status=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",success ? "SUCCEEDED" : "FAILED",id);
            db.update("UPDATE payment_order SET status=?,state_version=state_version+1 WHERE id=? AND status='REFUNDING'",success ? "REFUNDED" : "PAID",snapshot.orderId());
            if(success) db.update("UPDATE payment_order SET provider_refunded_cents=?,last_checked_at=CURRENT_TIMESTAMP WHERE id=?",snapshot.amountCents(),snapshot.orderId());
        });
    }
    public void finish(String id, String owner, String error) {
        if(get(id).checks()>=12) review(id,owner,error==null?"自动核对达到上限，请人工核对渠道账单":error);
        db.update("UPDATE payment_refund SET sync_owner=NULL,sync_until=NULL,next_check_at=TIMESTAMPADD(SECOND,CASE WHEN checks<3 THEN 30 WHEN checks<6 THEN 300 ELSE 1800 END,CURRENT_TIMESTAMP),last_error=? WHERE id=? AND sync_owner=?",error,id,owner);
    }

    /** Only REVIEW can be manually resolved. Evidence is a reference to an independently checked channel record. */
    public Refund resolve(String orderId,String actor,String outcome,String reason,String evidence) {
        if(actor==null||actor.isBlank()||actor.length()>64||!Set.of("SUCCEEDED","FAILED").contains(outcome==null?"":outcome)
                ||reason==null||reason.isBlank()||reason.length()>500||evidence==null||evidence.isBlank()||evidence.length()>256)
            throw new BillingException(400,"人工退款核对必须提供终态、原因和渠道账单/工单证据编号");
        return tx.execute(s -> {
            var orders=db.queryForList("SELECT * FROM payment_order WHERE id=? FOR UPDATE",orderId);
            if(orders.isEmpty())throw new BillingException(404,"订单不存在");
            var order=orders.getFirst();
            Refund snapshot=forOrder(orderId);
            var r=db.queryForMap("SELECT * FROM payment_refund WHERE id=? FOR UPDATE",snapshot.id());
            if(!"REVIEW".equals(r.get("status"))) {
                if(outcome.equals(r.get("status"))&&actor.equals(r.get("resolved_by"))&&reason.trim().equals(r.get("resolution_reason"))&&evidence.trim().equals(r.get("evidence_reference")))return get(snapshot.id());
                throw new BillingException(409,"只有待人工核对退款可以处理；已完成的结果不可覆盖");
            }
            if(!"REFUND_REVIEW".equals(order.get("status")))throw new BillingException(409,"订单与退款状态不一致");
            boolean success="SUCCEEDED".equals(outcome);
            if(!success) {
                String user=(String)order.get("user_id");
                db.queryForObject("SELECT balance_micros FROM billing_wallet WHERE user_id=? FOR UPDATE",Long.class,user);
                db.update("UPDATE billing_wallet SET balance_micros=balance_micros+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",Math.multiplyExact(snapshot.amountCents(),10000L),user);
            }
            PaymentService.ledger(db,orderId,snapshot.id(),success?"REFUND_SUCCEEDED":"REFUND_RELEASE",success?0:Math.multiplyExact(snapshot.amountCents(),10000L),actor);
            db.update("UPDATE payment_refund SET status=?,resolved_by=?,resolution_reason=?,evidence_reference=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",outcome,actor,reason.trim(),evidence.trim(),snapshot.id());
            db.update("UPDATE payment_order SET status=?,state_version=state_version+1,reconcile_state='MANUAL',last_error=NULL WHERE id=?",success?"REFUNDED":"PAID",orderId);
            return get(snapshot.id());
        });
    }
    private static Refund map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Refund(rs.getString("id"),rs.getString("order_id"),rs.getLong("amount_cents"),rs.getString("status"),rs.getString("actor"),rs.getString("reason"),rs.getInt("attempts"),rs.getInt("checks"),rs.getString("last_error"),rs.getString("resolved_by"),rs.getString("resolution_reason"),rs.getString("evidence_reference"));
    }
    public record Refund(String id,String orderId,long amountCents,String status,String actor,String reason,int attempts,int checks,String lastError,String resolvedBy,String resolutionReason,String evidenceReference) {}
}
