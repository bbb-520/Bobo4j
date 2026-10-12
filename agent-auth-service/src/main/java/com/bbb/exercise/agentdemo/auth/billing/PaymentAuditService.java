package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

/** Order wallet movements and signed provider observations; never claims to reconcile a whole bank account. */
@Service
public class PaymentAuditService {
    private final JdbcTemplate db;
    public PaymentAuditService(JdbcTemplate db) { this.db=db; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> report(int limit) {
        if(limit<1||limit>200)throw new BillingException(400,"查询条数须为 1～200");
        var rows=db.queryForList("SELECT id FROM payment_order WHERE channel='ALIPAY' ORDER BY COALESCE(last_checked_at,created_at) DESC LIMIT ?",limit);
        return rows.stream().map(row->summary((String)row.get("id"))).toList();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> audit(String orderId) {
        var result=new LinkedHashMap<>(summary(orderId));
        result.put("ledger",db.queryForList("SELECT event_type,delta_micros,refund_id,actor,created_at FROM payment_ledger WHERE order_id=? ORDER BY created_at,event_key",orderId));
        result.put("observations",db.queryForList("SELECT source,provider_status,provider_trade_id,amount_cents,receipt_cents,buyer_paid_cents,invoice_cents,refunded_cents,app_id,seller_id,created_at FROM payment_observation WHERE order_id=? ORDER BY created_at DESC LIMIT 100",orderId));
        result.put("refund",db.queryForList("SELECT id,status,amount_cents,attempts,checks,last_error,resolved_by,resolution_reason,evidence_reference,created_at,completed_at FROM payment_refund WHERE order_id=?",orderId));
        return result;
    }

    private Map<String,Object> summary(String id) {
        var rows=db.queryForList("SELECT * FROM payment_order WHERE id=?",id);
        if(rows.isEmpty())throw new BillingException(404,"订单不存在");
        var order=rows.getFirst();String status=(String)order.get("status");
        long amount=((Number)order.get("amount_cents")).longValue();
        var ledger=db.queryForMap("SELECT COALESCE(SUM(delta_micros),0) AS net,COALESCE(SUM(CASE WHEN event_type='CREDIT' THEN 1 ELSE 0 END),0) AS entries FROM payment_ledger WHERE order_id=?",id);
        long net=((Number)ledger.get("net")).longValue();long entries=((Number)ledger.get("entries")).longValue();
        long expected="PAID".equals(status)?Math.multiplyExact(amount,10000L):0;
        long held=Set.of("REFUNDING","REFUND_REVIEW").contains(status)?Math.multiplyExact(amount,10000L):0;
        var receipt=(Number)order.get("receipt_cents");var refunded=(Number)order.get("provider_refunded_cents");
        Long providerNet=receipt==null||refunded==null?null:Math.subtractExact(receipt.longValue(),refunded.longValue());
        Long difference=providerNet==null?null:Math.subtractExact(Math.multiplyExact(providerNet,10000L),Math.addExact(net,held));
        String state=entries==0?"LEGACY_UNVERIFIED":net!=expected?"LEDGER_MISMATCH":difference==null?"INCOMPLETE":difference!=0?"PROVIDER_MISMATCH":"MATCHED";
        if("REFUND_REVIEW".equals(status)||"ERROR".equals(order.get("reconcile_state")))state="REVIEW";
        if("MANUAL".equals(order.get("reconcile_state"))&&entries>0&&net==expected)state="MANUAL_EVIDENCE";
        var result=new LinkedHashMap<String,Object>();
        result.put("orderId",id);result.put("status",status);result.put("amountCents",amount);
        result.put("providerTradeId",order.get("provider_trade_id"));result.put("providerStatus",order.get("provider_status"));
        result.put("receiptCents",receipt);result.put("buyerPaidCents",order.get("buyer_paid_cents"));result.put("invoiceCents",order.get("invoice_cents"));
        result.put("providerRefundedCents",refunded);result.put("providerNetCents",providerNet);
        result.put("walletMovementMicros",net);result.put("expectedWalletMovementMicros",expected);result.put("heldRefundMicros",held);
        result.put("differenceMicros",difference);result.put("state",state);result.put("lastCheckedAt",order.get("last_checked_at"));result.put("lastError",order.get("last_error"));
        return result;
    }
}
