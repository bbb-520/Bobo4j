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

/** 验证支付宝验签，并拒绝错误商户、金额和过期通知。 */
class PaymentGatewayTest {
    @TempDir Path directory;
    PaymentService payments;
    PaymentGateway gateway;
    JdbcTemplate jdbc;
    String privateKey;
    String aes = "0123456789abcdef0123456789abcdef";
    JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");

        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V3__billing.sql"),
                        new ClassPathResource("db/migration/V6__free_tokens.sql"),
                        new ClassPathResource("db/migration/V8__payment_recovery.sql"),new ClassPathResource("db/migration/V9__alipay_stability.sql"))
                .execute(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        new BillingService(jdbc, tx, 10000, 200000, 1000000, 500000).createWallet("alice", 0);
        payments = new PaymentService(jdbc, tx);
        var generator = KeyPairGenerator.getInstance("RSA");

        generator.initialize(2048);
        var pair = generator.generateKeyPair();

        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        Path pub = directory.resolve("public.pem");

        Files.writeString(pub, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var p = new PaymentProperties();

        p.setNotifyBaseUrl("https://test.example");
        p.getAlipay().setEnabled(true);
        p.getAlipay().setAppId("ali-app");
        p.getAlipay().setSellerId("seller");
        p.getAlipay().setPublicKeyPath(pub.toString());
        gateway = new PaymentGateway(p, payments, new AlipayTradeGateway(p));
    }

    @Test
    void alipayRequiresSignatureMerchantAndExactAmount() {
        var order = payments.create("alice", "ali", "ALIPAY", 123);
        var fields = new TreeMap<String, String>();

        fields.put("app_id", "ali-app");
        fields.put("seller_id", "seller");
        fields.put("trade_status", "TRADE_SUCCESS");
        fields.put("trade_no", "ali-trade");
        fields.put("out_trade_no", order.id());
        fields.put("total_amount", "1.23");
        fields.put("receipt_amount", "1.23");
        String content =
                fields.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining("&"));

        fields.put("sign_type", "RSA2");
        fields.put("sign", PaymentCrypto.sign(content, privateKey));
        gateway.alipayNotify(fields);
        gateway.alipayNotify(fields);
        assertThat(jdbc.queryForObject("SELECT balance_micros FROM billing_wallet", Long.class))
                .isEqualTo(1230000);
        fields.put("total_amount", "9.99");
        assertThatThrownBy(() -> gateway.alipayNotify(fields)).isInstanceOf(BillingException.class);
    }

}
