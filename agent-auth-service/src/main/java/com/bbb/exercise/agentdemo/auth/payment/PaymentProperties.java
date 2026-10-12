package com.bbb.exercise.agentdemo.auth.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 支付集成配置，汇总回调地址以及支付宝的接入参数。 */
@Data
@ConfigurationProperties("app.payments")
public class PaymentProperties {
    private String notifyBaseUrl = "";
    private Alipay alipay = new Alipay();

    /** 支付宝接入配置，保存应用、商户、网关及签名密钥文件位置。 */
    @Data
    public static class Alipay {
        private boolean enabled;
        private String gateway = "https://openapi.alipay.com/gateway.do";
        private String appId = "", sellerId = "", privateKeyPath = "", publicKeyPath = "";
    }

}
