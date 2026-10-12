package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;

/** Payment truth is a verified provider notification, never a browser redirect. */
@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PaymentService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // 1. 充值订单创建：同一用户的幂等键只能对应相同渠道和金额。
    public Order create(String user, String key, String channel, long cents) {
        if (key == null
                || !key.matches("[A-Za-z0-9_.:-]{1,128}")
                || channel == null || !java.util.Set.of("ALIPAY", "WECHAT").contains(channel)
                || cents < 1
                || cents > 1000000) {
            throw new BillingException(400, "订单参数无效，充值范围为 0.01～10000 元");
        }

        String id = UUID.randomUUID().toString().replace("-", "");

        try {
            jdbc.update(
                    "INSERT INTO payment_order(id,user_id,idempotency_key,channel,amount_cents,expires_at) VALUES (?,?,?,?,?,TIMESTAMPADD(MINUTE,30,CURRENT_TIMESTAMP))",
                    id,
                    user,
                    key,
                    channel,
                    cents);
        } catch (DuplicateKeyException duplicate) {
            var rows =
                    jdbc.query(
                            "SELECT * FROM payment_order WHERE user_id=? AND idempotency_key=?",
                            (rs, n) -> map(rs),
                            user,
                            key);

            if (rows.isEmpty()) {
                throw duplicate;
            }

            var order = rows.getFirst();

            if (order.amountCents() != cents || !order.channel().equals(channel)) {
                throw new BillingException(409, "幂等键已经用于不同订单");
            }

            return order;
        }

        return get(user, id);
    }

    // 1.1. 订单查询：所有浏览器查询都同时约束订单 ID 和所属用户。
    public Order get(String user, String id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM payment_order WHERE id=? AND user_id=?",
                        (rs, n) -> map(rs),
                        id,
                        user);

        if (rows.isEmpty()) {
            throw new BillingException(404, "订单不存在");
        }

        return rows.getFirst();
    }

    // 2. 预下单结果：仅为待支付且尚无二维码的订单保存渠道返回值。
    public void qr(String user, String id, String owner, String code) {
        if (code == null || code.isBlank()) {
            throw new BillingException(502, "支付平台没有返回二维码");
        }

        jdbc.update(
                "UPDATE payment_order SET qr_code=? WHERE id=? AND user_id=? AND status='PENDING' AND qr_code IS NULL AND prepay_owner=? AND prepay_until>CURRENT_TIMESTAMP",
                code,
                id,
                user,
                owner);
    }

    // 2.1. 预下单租约：通过条件更新减少同一订单的并发重复下单。
    public String acquirePrepay(String user, String id) {
        String owner = UUID.randomUUID().toString();
        int changed =
                jdbc.update(
                        "UPDATE payment_order SET prepay_owner=?,prepay_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE id=? AND user_id=? AND status='PENDING' AND qr_code IS NULL AND expires_at>CURRENT_TIMESTAMP AND (prepay_until IS NULL OR prepay_until<CURRENT_TIMESTAMP) AND (sync_until IS NULL OR sync_until<CURRENT_TIMESTAMP)",
                        owner,
                        id,
                        user);

        if (changed != 1) {
            throw new BillingException(409, "订单下单中、已完成或已过期，请查询订单");
        }

        return owner;
    }

    // 2.2. 租约释放：只有当前租约持有者可以清除预下单占用。
    public void releasePrepay(String user, String id, String owner) {
        jdbc.update(
                "UPDATE payment_order SET prepay_owner=NULL,prepay_until=NULL WHERE id=? AND user_id=? AND prepay_owner=?",
                id,
                user,
                owner);
    }

    // 3. 支付入账：仅接收已验签的渠道通知，订单和钱包更新共享事务。
    public void credit(String id, String channel, String trade, long cents, String currency) {
        if (trade == null || trade.isBlank() || trade.length() > 128) {
            throw new BillingException(400, "支付交易号无效");
        }

        tx.executeWithoutResult(
                s -> {
                    var rows =
                            jdbc.query(
                                    "SELECT * FROM payment_order WHERE id=? FOR UPDATE",
                                    (rs, n) -> map(rs),
                                    id);

                    if (rows.isEmpty()) {
                        throw new BillingException(404, "订单不存在");
                    }

                    Order o = rows.getFirst();

                    if (!channel.equals(o.channel())
                            || cents != o.amountCents()
                            || !"CNY".equals(currency)) {
                        throw new BillingException(400, "回调金额、币种或支付渠道不匹配");
                    }

                    // 3.1. 重复通知：已支付订单必须匹配同一渠道交易号，匹配时直接返回。
                    if (java.util.Set.of("PAID", "REFUNDING", "REFUNDED").contains(o.status())) {
                        if (!trade.equals(o.providerTradeId())) {
                            throw new BillingException(409, "订单交易号不匹配");
                        }

                        return;
                    }

                    if (!java.util.Set.of("PENDING", "CLOSED").contains(o.status())) {
                        throw new BillingException(409, "订单状态不允许入账");
                    }

                    // Verified late notifications still credit real money; local expiry only
                    // prevents new prepay.
                    // 3.2. 一次入账：锁定钱包后将分转换为整数微元，并同时标记订单已支付。
                    jdbc.queryForObject(
                            "SELECT balance_micros FROM billing_wallet WHERE user_id=? FOR UPDATE",
                            Long.class,
                            o.userId());
                    jdbc.update(
                            "UPDATE payment_order SET status='PAID',provider_trade_id=?,paid_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND status IN ('PENDING','CLOSED')",
                            trade,
                            id);
                    jdbc.update(
                            "UPDATE billing_wallet SET balance_micros=balance_micros+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                            Math.multiplyExact(cents, 10000L),
                            o.userId());
                });
    }

    public Order find(String id) {
        var rows = jdbc.query("SELECT * FROM payment_order WHERE id=?", (rs,n) -> map(rs), id);
        if (rows.isEmpty()) throw new BillingException(404, "订单不存在");
        return rows.getFirst();
    }

    public java.util.List<Order> dueOrders() {
        return jdbc.query("SELECT * FROM payment_order WHERE channel='ALIPAY' AND status='PENDING' AND next_check_at<=CURRENT_TIMESTAMP ORDER BY next_check_at LIMIT 20",
                (rs,n) -> map(rs));
    }

    public String acquireSync(String id) {
        String owner = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE payment_order SET sync_owner=?,sync_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE id=? AND status='PENDING' AND next_check_at<=CURRENT_TIMESTAMP AND (sync_until IS NULL OR sync_until<CURRENT_TIMESTAMP) AND (prepay_until IS NULL OR prepay_until<CURRENT_TIMESTAMP)", owner,id);
        return changed == 1 ? owner : null;
    }

    public boolean expired(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at<=CURRENT_TIMESTAMP FROM payment_order WHERE id=?", Boolean.class,id));
    }

    public void closed(String id, String owner) {
        jdbc.update("UPDATE payment_order SET status='CLOSED',qr_code=NULL,last_error=NULL WHERE id=? AND status='PENDING' AND sync_owner=? AND sync_until>CURRENT_TIMESTAMP", id,owner);
    }

    public void finishSync(String id, String owner, String error) {
        jdbc.update("UPDATE payment_order SET sync_owner=NULL,sync_until=NULL,next_check_at=TIMESTAMPADD(SECOND,CASE WHEN expires_at<CURRENT_TIMESTAMP THEN 300 ELSE 10 END,CURRENT_TIMESTAMP),last_error=CASE WHEN status='PENDING' THEN ? ELSE NULL END WHERE id=? AND sync_owner=?", error,id,owner);
    }

    private static Order map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Order(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("channel"),
                rs.getLong("amount_cents"),
                rs.getString("status"),
                rs.getString("provider_trade_id"),
                rs.getString("qr_code"),
                rs.getTimestamp("expires_at").toInstant());
    }

    /** 支付订单快照，保存所属用户、渠道、金额、二维码和到账状态。 */
    public record Order(
            String id,
            String userId,
            String channel,
            long amountCents,
            String status,
            String providerTradeId,
            String qrCode,
            Instant expiresAt) {}
}
