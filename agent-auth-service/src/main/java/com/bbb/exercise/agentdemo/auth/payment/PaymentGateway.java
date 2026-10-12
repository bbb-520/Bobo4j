package com.bbb.exercise.agentdemo.auth.payment;

import com.alipay.api.internal.util.AlipaySignature;
import com.bbb.exercise.agentdemo.auth.billing.BillingException;
import com.bbb.exercise.agentdemo.auth.billing.PaymentService;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Alipay face-to-face integration, with verified server notifications only. */
@Component
public class PaymentGateway {
    private final PaymentProperties properties;
    private final PaymentService orders;
    private final AlipayTradeGateway alipay;

    public PaymentGateway(PaymentProperties properties, PaymentService orders, AlipayTradeGateway alipay) {
        this.properties = properties;
        this.orders = orders;
        this.alipay = alipay;
    }

    public void requireEnabled(String channel) {
        if (!"ALIPAY".equals(channel)) throw new BillingException(400,"仅支持支付宝支付");
        boolean enabled = properties.getAlipay().isEnabled();

        if (!enabled) {
            throw new BillingException(503, "该支付渠道尚未配置");
        }

        if (!properties.getNotifyBaseUrl().startsWith("https://")) {
            throw new BillingException(503, "支付回调必须配置公网 HTTPS 地址");
        }
    }

    // 1. 渠道预下单：仅使用支付宝协议。
    public String prepay(PaymentService.Order order) {
        requireEnabled(order.channel());
        // Expiry and the prepay lease are validated by PaymentService against the database clock.
        return alipay.prepay(order);
    }

    // 2. 支付宝通知：验证签名和交易归属后，仅接受符合条件的支付结果。
    public void alipayNotify(Map<String, String> fields) {
        requireEnabled("ALIPAY");
        var p = properties.getAlipay();

        required(p.getAppId(), p.getSellerId(), p.getPublicKeyPath());

        try {
            if (!"RSA2".equals(fields.get("sign_type"))
                    || !AlipaySignature.rsaCheckV1(
                            new HashMap<>(fields),
                            PaymentCrypto.pem(p.getPublicKeyPath()),
                            "UTF-8",
                            "RSA2")) {
                throw new BillingException(401, "支付宝签名无效");
            }

            if (!p.getAppId().equals(fields.get("app_id"))
                    || !p.getSellerId().equals(fields.get("seller_id"))) {
                throw new BillingException(400, "支付宝商户不匹配");
            }

            String state = fields.get("trade_status");

            if (!Set.of("TRADE_SUCCESS", "TRADE_FINISHED").contains(state)) {
                return;
            }

            long cents =
                    new BigDecimal(fields.get("total_amount")).movePointRight(2).longValueExact();
            var order=orders.find(fields.get("out_trade_no"));
            if(!"ALIPAY".equals(order.channel())||cents!=order.amountCents()||fields.get("trans_currency")!=null&&!"CNY".equals(fields.get("trans_currency")))
                throw new BillingException(400,"支付宝通知渠道、金额或币种不匹配");
            Long receipt=amount(fields.get("receipt_amount")),buyer=amount(fields.get("buyer_pay_amount")),invoice=amount(fields.get("invoice_amount"));
            for(Long value:new Long[]{receipt,buyer,invoice})if(value!=null&&(value<0||value>cents))throw new BillingException(400,"支付宝通知金额明细无效");
            if(receipt!=null&&receipt!=cents)throw new BillingException(400,"支付宝通知实收金额不匹配");
            if(fields.get("trade_no")==null||fields.get("trade_no").isBlank()||order.providerTradeId()!=null&&!order.providerTradeId().equals(fields.get("trade_no")))
                throw new BillingException(400,"支付宝通知交易号不匹配");
            orders.observe(order.id(),"CALLBACK",state,fields.get("trade_no"),cents,receipt,buyer,invoice,null,p.getAppId(),p.getSellerId());
            // Missing receipt detail is not proof of cash received. A verified query must finish reconciliation.
            if(receipt!=null) orders.credit(order.id(),"ALIPAY",fields.get("trade_no"),cents,"CNY");
        } catch (BillingException e) {
            throw e;
        } catch (Exception e) {
            throw new BillingException(400, "支付宝回调无效");
        }
    }

    private static Long amount(String value) {
        return value==null||value.isBlank()?null:new BigDecimal(value).movePointRight(2).longValueExact();
    }

    private static void required(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new BillingException(503, "支付商户配置不完整");
            }
        }
    }
}
