package com.bbb.exercise.agentdemo.auth.payment;

import com.bbb.exercise.agentdemo.auth.billing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PaymentGatewayTest {
    @TempDir Path directory;
    PaymentService payments;PaymentGateway gateway;JdbcTemplate jdbc;String privateKey;
    String aes="0123456789abcdef0123456789abcdef";JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"), new ClassPathResource("db/migration/V6__free_tokens.sql")).execute(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new BillingService(jdbc,tx,10000,200000,1000000,500000).createWallet("alice",0);payments=new PaymentService(jdbc,tx);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        privateKey=Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        Path pub=directory.resolve("public.pem");Files.writeString(pub,Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var p=new PaymentProperties();p.setNotifyBaseUrl("https://test.example");p.getWechat().setEnabled(true);p.getWechat().setAppId("wx-app");p.getWechat().setMerchantId("merchant");p.getWechat().setApiV3Key(aes);p.getWechat().setPublicKeyId("PUB_KEY_ID_test");p.getWechat().setPublicKeyPath(pub.toString());
        p.getAlipay().setEnabled(true);p.getAlipay().setAppId("ali-app");p.getAlipay().setSellerId("seller");p.getAlipay().setPublicKeyPath(pub.toString());gateway=new PaymentGateway(p,payments);
    }
    @Test void wechatAuthenticatesDecryptsAndCreditsOnce() throws Exception {
        var order=payments.create("alice","wx","WECHAT",123);
        String raw=notification(order.id(),"merchant",123);var headers=headers(raw);
        gateway.wechatNotify(headers,raw);gateway.wechatNotify(headers,raw);
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet",Long.class)).isEqualTo(1230000);
        assertThatThrownBy(() -> gateway.wechatNotify(headers,raw+" ")).isInstanceOf(BillingException.class);
    }
    @Test void wechatRejectsWrongMerchantAndExpiredSignature() throws Exception {
        var order=payments.create("alice","wx","WECHAT",123);String raw=notification(order.id(),"attacker",123);
        assertThatThrownBy(() -> gateway.wechatNotify(headers(raw),raw)).isInstanceOf(BillingException.class);
        var h=headers(raw);h.set("Wechatpay-Timestamp","1");assertThatThrownBy(() -> gateway.wechatNotify(h,raw)).isInstanceOf(BillingException.class);
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet",Long.class)).isZero();
    }
    @Test void alipayRequiresSignatureMerchantAndExactAmount() {
        var order=payments.create("alice","ali","ALIPAY",123);
        var fields=new TreeMap<String,String>();fields.put("app_id","ali-app");fields.put("seller_id","seller");fields.put("trade_status","TRADE_SUCCESS");fields.put("trade_no","ali-trade");fields.put("out_trade_no",order.id());fields.put("total_amount","1.23");
        String content=fields.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("&"));
        fields.put("sign_type","RSA2");fields.put("sign",PaymentCrypto.sign(content,privateKey));
        gateway.alipayNotify(fields);gateway.alipayNotify(fields);
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet",Long.class)).isEqualTo(1230000);
        fields.put("total_amount","9.99");assertThatThrownBy(() -> gateway.alipayNotify(fields)).isInstanceOf(BillingException.class);
    }
    private HttpHeaders headers(String raw) {
        String time=Long.toString(Instant.now().getEpochSecond()),nonce="notification-nonce";var h=new HttpHeaders();h.set("Wechatpay-Timestamp",time);h.set("Wechatpay-Nonce",nonce);h.set("Wechatpay-Serial","PUB_KEY_ID_test");h.set("Wechatpay-Signature",PaymentCrypto.sign(time+"\n"+nonce+"\n"+raw+"\n",privateKey));return h;
    }
    private String notification(String order,String merchant,long cents) throws Exception {
        String plain=json.writeValueAsString(Map.of("appid","wx-app","mchid",merchant,"trade_state","SUCCESS","trade_type","NATIVE","out_trade_no",order,"transaction_id","wx-trade","amount",Map.of("total",cents,"currency","CNY")));
        String nonce="0123456789ab",aad="transaction";var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String cipher=Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        return json.writeValueAsString(Map.of("event_type","TRANSACTION.SUCCESS","resource",Map.of("algorithm","AEAD_AES_256_GCM","nonce",nonce,"associated_data",aad,"ciphertext",cipher)));
    }
}
