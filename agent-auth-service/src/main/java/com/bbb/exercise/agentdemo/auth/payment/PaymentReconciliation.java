package com.bbb.exercise.agentdemo.auth.payment;

import com.bbb.exercise.agentdemo.auth.billing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Provider reconciliation uses database leases; transient failures never manufacture financial results. */
@Component
public class PaymentReconciliation {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);
    private final PaymentProperties properties;
    private final PaymentService orders;
    private final PaymentRefundService refunds;
    private final AlipayTradeGateway alipay;
    public PaymentReconciliation(PaymentProperties properties, PaymentService orders,
                                 PaymentRefundService refunds, AlipayTradeGateway alipay) {
        this.properties = properties; this.orders = orders; this.refunds = refunds; this.alipay = alipay;
    }

    public PaymentService.Order refresh(String user, String id) {
        var order = orders.get(user,id); // Ownership check always precedes external requests.
        if (properties.getAlipay().isEnabled() && "ALIPAY".equals(order.channel()) && Set.of("PENDING","PAID").contains(order.status())) sync(order);
        return orders.get(user,id);
    }

    private void sync(PaymentService.Order order) {
        String owner = orders.acquireSync(order.id());
        if (owner == null) return;
        String error = null;
        try {
            var trade = alipay.query(order);
            if(!"NOT_FOUND".equals(trade.status()) && !orders.observeQuery(order.id(),owner,trade.status(),trade.tradeId(),trade.amountCents(),trade.receiptCents(),trade.buyerPaidCents(),trade.invoiceCents(),trade.refundedCents(),properties.getAlipay().getAppId(),properties.getAlipay().getSellerId())) return;
            if("PAID".equals(order.status())&&!Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(trade.status()))
                throw new BillingException(502,"已入账订单与渠道状态不一致，请人工核对");
            if (Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(trade.status())) {
                orders.credit(order.id(),"ALIPAY",trade.tradeId(),trade.amountCents(),"CNY");
            } else if ("TRADE_CLOSED".equals(trade.status())) {
                orders.closed(order.id(),owner);
            } else if ("WAIT_BUYER_PAY".equals(trade.status()) && orders.expired(order.id())) {
                alipay.close(order);
                orders.closed(order.id(),owner);
            }
            // NOT_FOUND is not proof of payment failure after a timeout during precreation.
        } catch (Exception failure) {
            error = error(failure);
            log.warn("Alipay order {} reconciliation deferred: {}",order.id(),error);
        } finally { orders.finishSync(order.id(),owner,error); }
    }

    public PaymentRefundService.Refund refund(String orderId, String actor, String reason) {
        if (!properties.getAlipay().isEnabled()) throw new BillingException(503,"支付宝尚未启用");
        var refund = refunds.begin(orderId,actor,reason);
        syncRefund(refund);
        return refunds.get(refund.id());
    }

    private void syncRefund(PaymentRefundService.Refund refund) {
        String owner = refunds.acquire(refund.id());
        if (owner == null) return;
        String error = null;
        int attempt = 0;
        try {
            if(refunds.budgetExhausted(refund.id())) {
                refunds.review(refund.id(),owner,"自动退款核对超过次数或 24 小时上限，请核对渠道账单");
                return;
            }
            var order = orders.find(refund.orderId());
            var state = alipay.refundQuery(order,refund);
            if (state == AlipayTradeGateway.RefundState.SUCCEEDED) refunds.succeed(refund.id(),owner);
            else if (state == AlipayTradeGateway.RefundState.MISSING) {
                attempt = refunds.attempt(refund.id(),owner);
                if (alipay.refund(order,refund)) refunds.succeed(refund.id(),owner);
            }
        } catch (AlipayTradeGateway.RejectedRefund rejection) {
            error = error(rejection);
            // Later rejections never resolve an earlier ambiguous attempt.
            if (attempt == 1) refunds.rejectFirstAttempt(refund.id(),owner);
            log.warn("Alipay refund {} rejected: {}",refund.id(),error);
        } catch (Exception failure) {
            error = error(failure);
            log.warn("Alipay refund {} confirmation deferred: {}",refund.id(),error);
        } finally { refunds.finish(refund.id(),owner,error); }
    }

    @Scheduled(fixedDelayString = "${app.payments.reconcile-delay-ms:15000}", initialDelay = 15000)
    public void runOnce() {
        if (!properties.getAlipay().isEnabled()) return;
        // Refunds first so a backlog of unpaid orders cannot delay financial recovery.
        for (var refund : refunds.dueRefunds()) syncRefund(refund);
        for (var order : orders.dueOrders()) sync(order);
    }
    private static String error(Exception e) {
        String message = e instanceof BillingException b ? b.getReason() : e.getClass().getSimpleName();
        return message == null ? "支付恢复暂不可用" : message.substring(0,Math.min(256,message.length()));
    }
}
