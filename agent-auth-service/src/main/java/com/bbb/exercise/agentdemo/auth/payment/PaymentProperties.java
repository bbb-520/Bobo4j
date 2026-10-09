package com.bbb.exercise.agentdemo.auth.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties("app.payments")
public class PaymentProperties {
    private String notifyBaseUrl="";
    private Alipay alipay=new Alipay();
    private Wechat wechat=new Wechat();
    @Data public static class Alipay {
        private boolean enabled;
        private String gateway="https://openapi.alipay.com/gateway.do";
        private String appId="",sellerId="",privateKeyPath="",publicKeyPath="";
    }
    @Data public static class Wechat {
        private boolean enabled;
        private String appId="",merchantId="",merchantSerial="",privateKeyPath="",apiV3Key="";
        private String publicKeyId="",publicKeyPath="";
        private Map<String,String> additionalPublicKeys=new HashMap<>();
    }
}
