package com.bbb.exercise.agentdemo.auth.payment;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.internal.util.AlipaySignature;
import com.bbb.exercise.agentdemo.auth.billing.BillingException;
import com.bbb.exercise.agentdemo.auth.billing.PaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Alipay face-to-face and WeChat Native integrations, with verified server notifications only. */
@Component
public class PaymentGateway {
    private final PaymentProperties properties;private final PaymentService orders;
    private final JsonMapper json=JsonMapper.builder().build();
    private final WebClient wechat=WebClient.builder().baseUrl("https://api.mch.weixin.qq.com").build();
    public PaymentGateway(PaymentProperties properties,PaymentService orders) {this.properties=properties;this.orders=orders;}
    public void requireEnabled(String channel) {
        boolean enabled="ALIPAY".equals(channel)?properties.getAlipay().isEnabled():"WECHAT".equals(channel)&&properties.getWechat().isEnabled();
        if(!enabled) throw new BillingException(503,"该支付渠道尚未配置");
        if(!properties.getNotifyBaseUrl().startsWith("https://")) throw new BillingException(503,"支付回调必须配置公网 HTTPS 地址");
    }
    public String prepay(PaymentService.Order order) {
        requireEnabled(order.channel());
        // Expiry and the prepay lease are validated by PaymentService against the database clock.
        return "ALIPAY".equals(order.channel())?alipayPrepay(order):wechatPrepay(order);
    }
    private String alipayPrepay(PaymentService.Order order) {
        var p=properties.getAlipay();required(p.getAppId(),p.getSellerId(),p.getPrivateKeyPath(),p.getPublicKeyPath());
        try {
            var config=new com.alipay.api.AlipayConfig();config.setServerUrl(p.getGateway());config.setAppId(p.getAppId());
            config.setPrivateKey(PaymentCrypto.pem(p.getPrivateKeyPath()));config.setAlipayPublicKey(PaymentCrypto.pem(p.getPublicKeyPath()));
            config.setFormat("json");config.setCharset("UTF-8");config.setSignType("RSA2");config.setConnectTimeout(3000);config.setReadTimeout(10000);
            var client=new DefaultAlipayClient(config);
            var request=new AlipayTradePrecreateRequest();request.setNotifyUrl(properties.getNotifyBaseUrl()+"/api/payments/callback/alipay");
            request.setBizContent(json.writeValueAsString(Map.of("out_trade_no",order.id(),"total_amount",BigDecimal.valueOf(order.amountCents(),2).toPlainString(),"subject","BoboWorld 账户充值","timeout_express","30m")));
            var response=client.execute(request);
            if(!response.isSuccess()||response.getQrCode()==null) throw new BillingException(502,"支付宝下单失败，请稍后重试");return response.getQrCode();
        }catch(BillingException e) {throw e;}catch(Exception e) {throw new BillingException(502,"支付宝暂不可用");}
    }
    public void alipayNotify(Map<String,String> fields) {
        requireEnabled("ALIPAY");var p=properties.getAlipay();required(p.getAppId(),p.getSellerId(),p.getPublicKeyPath());
        try {
            if(!"RSA2".equals(fields.get("sign_type"))||!AlipaySignature.rsaCheckV1(new HashMap<>(fields),PaymentCrypto.pem(p.getPublicKeyPath()),"UTF-8","RSA2")) throw new BillingException(401,"支付宝签名无效");
            if(!p.getAppId().equals(fields.get("app_id"))||!p.getSellerId().equals(fields.get("seller_id"))) throw new BillingException(400,"支付宝商户不匹配");
            String state=fields.get("trade_status");if(!Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(state)) return;
            long cents=new BigDecimal(fields.get("total_amount")).movePointRight(2).longValueExact();
            orders.credit(fields.get("out_trade_no"),"ALIPAY",fields.get("trade_no"),cents,"CNY");
        }catch(BillingException e) {throw e;}catch(Exception e) {throw new BillingException(400,"支付宝回调无效");}
    }
    private String wechatPrepay(PaymentService.Order order) {
        var p=properties.getWechat();required(p.getAppId(),p.getMerchantId(),p.getMerchantSerial(),p.getPrivateKeyPath(),p.getPublicKeyId(),p.getPublicKeyPath());
        String path="/v3/pay/transactions/native";
        String body=json.writeValueAsString(Map.of("appid",p.getAppId(),"mchid",p.getMerchantId(),"description","BoboWorld 账户充值","out_trade_no",order.id(),
                "notify_url",properties.getNotifyBaseUrl()+"/api/payments/callback/wechat","time_expire",order.expiresAt().atZone(ZoneId.of("Asia/Shanghai")).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "amount",Map.of("total",order.amountCents(),"currency","CNY")));
        String nonce=UUID.randomUUID().toString().replace("-","");String timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=PaymentCrypto.sign("POST\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+body+"\n",PaymentCrypto.pem(p.getPrivateKeyPath()));
        String authorization="WECHATPAY2-SHA256-RSA2048 mchid=\""+p.getMerchantId()+"\",nonce_str=\""+nonce+"\",timestamp=\""+timestamp+"\",serial_no=\""+p.getMerchantSerial()+"\",signature=\""+signature+"\"";
        try {
            return wechat.post().uri(path).header("Authorization",authorization).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchangeToMono(response -> response.bodyToMono(String.class).map(raw -> {
                        verifyWechat(response.headers().asHttpHeaders(),raw);
                        if(!response.statusCode().is2xxSuccessful()) throw new BillingException(502,"微信下单失败，请稍后重试");
                        String code=json.readTree(raw).path("code_url").asText();if(code.isBlank()) throw new BillingException(502,"微信没有返回二维码");return code;
                    })).timeout(Duration.ofSeconds(10)).block();
        }catch(BillingException e) {throw e;}catch(Exception e) {throw new BillingException(502,"微信支付暂不可用");}
    }
    public void wechatNotify(HttpHeaders headers,String raw) {
        requireEnabled("WECHAT");verifyWechat(headers,raw);var p=properties.getWechat();
        try {
            JsonNode envelope=json.readTree(raw);
            if(!"TRANSACTION.SUCCESS".equals(envelope.path("event_type").asText())) throw new BillingException(400,"微信事件类型无效");
            JsonNode resource=envelope.path("resource");if(!"AEAD_AES_256_GCM".equals(resource.path("algorithm").asText())) throw new BillingException(400,"微信加密算法无效");
            String plain=PaymentCrypto.decrypt(p.getApiV3Key(),resource.path("nonce").asText(),resource.path("associated_data").asText(null),resource.path("ciphertext").asText());
            JsonNode trade=json.readTree(plain);
            if(!p.getAppId().equals(trade.path("appid").asText())||!p.getMerchantId().equals(trade.path("mchid").asText())||!"SUCCESS".equals(trade.path("trade_state").asText())||!"NATIVE".equals(trade.path("trade_type").asText())) throw new BillingException(400,"微信商户或交易状态不匹配");
            JsonNode amount=trade.path("amount");if(!amount.path("total").isIntegralNumber()) throw new BillingException(400,"微信金额无效");
            orders.credit(trade.path("out_trade_no").asText(),"WECHAT",trade.path("transaction_id").asText(),amount.path("total").asLong(),amount.path("currency").asText());
        }catch(BillingException e) {throw e;}catch(Exception e) {throw new BillingException(400,"微信回调无效");}
    }
    private void verifyWechat(HttpHeaders headers,String raw) {
        String timestamp=single(headers,"Wechatpay-Timestamp"),nonce=single(headers,"Wechatpay-Nonce"),serial=single(headers,"Wechatpay-Serial"),signature=single(headers,"Wechatpay-Signature");
        PaymentCrypto.timestamp(timestamp);var p=properties.getWechat();
        String path=serial.equals(p.getPublicKeyId())?p.getPublicKeyPath():p.getAdditionalPublicKeys().get(serial);
        if(path==null||path.isBlank()) throw new BillingException(401,"未知微信支付验签公钥 ID");
        PaymentCrypto.verify(timestamp+"\n"+nonce+"\n"+raw+"\n",signature,PaymentCrypto.pem(path));
    }
    private static String single(HttpHeaders h,String name) {var values=h.get(name);if(values==null||values.size()!=1||values.getFirst().isBlank()) throw new BillingException(401,"支付签名头无效");return values.getFirst();}
    private static void required(String... values) {for(String value:values) if(value==null||value.isBlank()) throw new BillingException(503,"支付商户配置不完整");}
}
