package com.bbb.exercise.agentdemo.auth.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

/** 支付集成配置，汇总回调地址以及支付宝和微信支付的接入参数。 */
@Data
@ConfigurationProperties("app.payments")
public class PaymentProperties {
    private String notifyBaseUrl = "";
    private Alipay alipay = new Alipay();
    private Wechat wechat = new Wechat();

    /** 支付宝接入配置，保存应用、商户、网关及签名密钥文件位置。 */
    @Data
    public static class Alipay {
        private boolean enabled;
        private String gateway = "https://openapi.alipay.com/gateway.do";
        private String appId = "", sellerId = "", privateKeyPath = "", publicKeyPath = "";
    }

    /** 微信支付接入配置，保存商户、证书标识及 API v3 验签解密参数。 */
    @Data
    public static class Wechat {
        private boolean enabled;
        private String appId = "",
                merchantId = "",
                merchantSerial = "",
                privateKeyPath = "",
                apiV3Key = "";
        private String publicKeyId = "", publicKeyPath = "";
        private Map<String, String> additionalPublicKeys = new HashMap<>();
    }
}
