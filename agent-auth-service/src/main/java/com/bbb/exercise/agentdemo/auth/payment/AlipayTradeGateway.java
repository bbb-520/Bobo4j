package com.bbb.exercise.agentdemo.auth.payment;

import com.alipay.api.*;
import com.alipay.api.request.*;
import com.bbb.exercise.agentdemo.auth.billing.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/** SDK performs request signing and response verification; no network I/O inside wallet transactions. */
@Component
public class AlipayTradeGateway {
    private final PaymentProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    public AlipayTradeGateway(PaymentProperties properties) { this.properties = properties; }

    private AlipayClient client() throws AlipayApiException {
        var p = properties.getAlipay();
        if (!p.isEnabled() || p.getAppId().isBlank() || p.getSellerId().isBlank()
                || p.getPrivateKeyPath().isBlank() || p.getPublicKeyPath().isBlank()) {
            throw new BillingException(503, "支付宝商户配置不完整或未启用");
        }
        var config = new AlipayConfig();
        config.setServerUrl(p.getGateway());
        config.setAppId(p.getAppId());
        config.setPrivateKey(PaymentCrypto.pem(p.getPrivateKeyPath()));
        config.setAlipayPublicKey(PaymentCrypto.pem(p.getPublicKeyPath()));
        config.setFormat("json");
        config.setCharset("UTF-8");
        config.setSignType("RSA2");
        config.setConnectTimeout(3000);
        config.setReadTimeout(10000);
        return new DefaultAlipayClient(config);
    }

    private <T extends AlipayResponse> T execute(AlipayRequest<T> request, Map<String, ?> body) {
        try {
            // All requests used here are JSON business requests supported by the SDK.
            if (request instanceof AlipayTradePrecreateRequest r) r.setBizContent(json.writeValueAsString(body));
            else if (request instanceof AlipayTradeQueryRequest r) r.setBizContent(json.writeValueAsString(body));
            else if (request instanceof AlipayTradeCloseRequest r) r.setBizContent(json.writeValueAsString(body));
            else if (request instanceof AlipayTradeRefundRequest r) r.setBizContent(json.writeValueAsString(body));
            else if (request instanceof AlipayTradeFastpayRefundQueryRequest r) r.setBizContent(json.writeValueAsString(body));
            return client().execute(request);
        } catch (BillingException e) { throw e; }
        catch (Exception e) {
            throw new BillingException(502, "支付宝通信或响应验签失败，请稍后查询订单", e);
        }
    }

    public String prepay(PaymentService.Order order) {
        if (!properties.getNotifyBaseUrl().startsWith("https://"))
            throw new BillingException(503, "支付回调必须配置公网 HTTPS 地址");
        var request = new AlipayTradePrecreateRequest();
        request.setNotifyUrl(properties.getNotifyBaseUrl() + "/api/payments/callback/alipay");
        var response = execute(request, Map.of("out_trade_no", order.id(), "total_amount", money(order.amountCents()),
                "subject", "BoboWorld 账户充值", "timeout_express", "30m",
                "time_expire", order.expiresAt().atZone(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        requireSuccess(response);
        requireOrder(order, response.getOutTradeNo());
        if (response.getQrCode() == null || response.getQrCode().isBlank())
            throw new BillingException(502, "支付宝没有返回付款二维码");
        return response.getQrCode();
    }

    public Trade query(PaymentService.Order order) {
        var r = execute(new AlipayTradeQueryRequest(), Map.of("out_trade_no", order.id()));
        if (!r.isSuccess() && "ACQ.TRADE_NOT_EXIST".equals(r.getSubCode())) return new Trade("NOT_FOUND", null, 0);
        requireSuccess(r);
        requireOrder(order, r.getOutTradeNo());
        long cents = cents(r.getTotalAmount());
        if (cents != order.amountCents()) throw new BillingException(502, "支付宝查单金额不匹配");
        if (r.getTransCurrency() != null && !"CNY".equals(r.getTransCurrency()))
            throw new BillingException(502, "支付宝查单币种不匹配");
        if (r.getTradeNo() == null || r.getTradeNo().isBlank())
            throw new BillingException(502, "支付宝查单交易号为空");
        return new Trade(r.getTradeStatus(), r.getTradeNo(), cents);
    }

    public void close(PaymentService.Order order) {
        var r = execute(new AlipayTradeCloseRequest(), Map.of("out_trade_no", order.id()));
        requireSuccess(r);
        requireOrder(order, r.getOutTradeNo());
    }

    public RefundState refundQuery(PaymentService.Order order, PaymentRefundService.Refund refund) {
        var r = execute(new AlipayTradeFastpayRefundQueryRequest(),
                Map.of("out_trade_no", order.id(), "out_request_no", refund.id()));
        if (!r.isSuccess() && Set.of("ACQ.TRADE_NOT_EXIST", "ACQ.REFUND_NOT_EXIST").contains(r.getSubCode()))
            return RefundState.MISSING;
        requireSuccess(r);
        // Alipay omits refund_status when this request has no refund record.
        if (r.getRefundStatus() == null || r.getRefundStatus().isBlank()) return RefundState.MISSING;
        requireOrder(order, r.getOutTradeNo());
        if (!refund.id().equals(r.getOutRequestNo()) || !order.providerTradeId().equals(r.getTradeNo())
                || cents(r.getRefundAmount()) != refund.amountCents())
            throw new BillingException(502, "支付宝退款查询信息不匹配");
        return "REFUND_SUCCESS".equals(r.getRefundStatus()) ? RefundState.SUCCEEDED : RefundState.PENDING;
    }

    public boolean refund(PaymentService.Order order, PaymentRefundService.Refund refund) {
        var r = execute(new AlipayTradeRefundRequest(), Map.of("out_trade_no", order.id(),
                "trade_no", order.providerTradeId(), "out_request_no", refund.id(),
                "refund_amount", money(refund.amountCents()), "refund_reason", refund.reason()));
        if (!r.isSuccess()) {
            // The SDK accepts unsigned error responses. Such errors may diagnose/retry,
            // but must never authorize restoration of spendable wallet funds.
            // A nonempty response signature was verified by the SDK during execute().
            if (Set.of("ACQ.SELLER_BALANCE_NOT_ENOUGH", "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL",
                    "ACQ.REFUND_AMOUNT_ERROR", "ACQ.TRADE_HAS_FINISHED").contains(r.getSubCode())
                    && !json.readTree(r.getBody()).path("sign").asText("").isBlank())
                throw new RejectedRefund(r.getSubCode());
            requireSuccess(r);
        }
        requireOrder(order, r.getOutTradeNo());
        if (!order.providerTradeId().equals(r.getTradeNo()) || cents(r.getRefundFee()) != refund.amountCents())
            throw new BillingException(502, "支付宝退款结果信息不匹配");
        // code=10000 alone is not financial confirmation. N/missing must be queried.
        return "Y".equals(r.getFundChange());
    }

    private static void requireOrder(PaymentService.Order order, String id) {
        if (!order.id().equals(id)) throw new BillingException(502, "支付宝返回订单号不匹配");
    }
    private static void requireSuccess(AlipayResponse r) {
        if (!r.isSuccess()) {
            String code = r.getSubCode() == null ? r.getCode() : r.getSubCode();
            if ("isv.not-online-app".equals(code))
                throw new BillingException(503, "支付宝应用尚未上线（isv.not-online-app），暂不能充值，请联系管理员完成应用上线审核");
            throw new BillingException(502, "支付宝请求失败（" + code + "），请核对商户权限或稍后重试");
        }
    }
    private static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    private static long cents(String amount) {
        try { return new BigDecimal(amount).movePointRight(2).longValueExact(); }
        catch (Exception e) { throw new BillingException(502, "支付宝返回金额无效"); }
    }
    public record Trade(String status, String tradeId, long amountCents) {}
    public enum RefundState { MISSING, PENDING, SUCCEEDED }
    public static class RejectedRefund extends BillingException {
        public RejectedRefund(String code) { super(502, "支付宝拒绝退款（" + code + "）"); }
    }
}
