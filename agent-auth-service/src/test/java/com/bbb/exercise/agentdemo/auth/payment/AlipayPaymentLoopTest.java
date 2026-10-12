package com.bbb.exercise.agentdemo.auth.payment;

import com.bbb.exercise.agentdemo.auth.billing.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;

/** Real SDK against a local signed Alipay HTTP fixture; real transactional wallet storage. */
class AlipayPaymentLoopTest {
    @TempDir Path directory;
    JdbcTemplate db;
    PaymentService orders;
    PaymentReconciliation recovery;
    AlipayTradeGateway alipay;
    PaymentProperties properties;
    HttpServer server;
    JsonMapper json = JsonMapper.builder().build();
    String signingKey;
    boolean tamper;
    boolean omitSignature;
    boolean blankSignature;
    List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    BiFunction<String, JsonNode, Map<String, Object>> reply;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        db = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__billing.sql"),
                new ClassPathResource("db/migration/V6__free_tokens.sql"),
                new ClassPathResource("db/migration/V8__payment_recovery.sql")).execute(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        new BillingService(db, tx, 10000, 200000, 1000000, 500000).createWallet("alice", 0);
        orders = new PaymentService(db, tx);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        signingKey = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        var privateFile = directory.resolve("private.pem");
        var publicFile = directory.resolve("public.pem");
        // Merchant and platform keys are different in the real protocol; SDK rejects identical keys.
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()));
        Files.writeString(publicFile, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", exchange -> {
            try {
                var form = new HashMap<String, String>();
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String query = exchange.getRequestURI().getRawQuery();
                for (String pair : (raw + (query == null ? "" : "&" + query)).split("&")) {
                    var parts = pair.split("=", 2);
                    if (parts.length == 2) form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
                requests.add(form);
                String method = form.get("method");
                var body = json.readTree(form.get("biz_content"));
                var result = new LinkedHashMap<String, Object>();
                result.put("code", "10000");
                result.put("msg", "Success");
                result.putAll(reply.apply(method, body));
                String signed = json.writeValueAsString(result);
                String response = "{\"" + method.replace('.', '_') + "_response\":" + signed
                        + (omitSignature ? "}" : ",\"sign\":\"" + (blankSignature ? "" : PaymentCrypto.sign(tamper ? signed + " " : signed, signingKey)) + "\"}");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
        properties = new PaymentProperties();
        properties.setNotifyBaseUrl("https://payments.example");
        var p = properties.getAlipay();
        p.setEnabled(true);
        p.setAppId("test-app");
        p.setSellerId("test-seller");
        p.setPrivateKeyPath(privateFile.toString());
        p.setPublicKeyPath(publicFile.toString());
        p.setGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.do");
        alipay = new AlipayTradeGateway(properties);
        recovery = new PaymentReconciliation(properties, orders, new PaymentRefundService(db, tx), alipay);
        reply = (method, body) -> trade(body, "WAIT_BUYER_PAY", "1.23");
    }

    @AfterEach void close() { if (server != null) server.stop(0); }

    PaymentService.Order order() {
        var order = orders.create("alice", UUID.randomUUID().toString(), "ALIPAY", 123);
        String lease = orders.acquirePrepay("alice", order.id());
        orders.qr("alice", order.id(), lease, "https://qr.alipay.com/test");
        orders.releasePrepay("alice", order.id(), lease);
        return order;
    }
    Map<String, Object> trade(JsonNode body, String status, String amount) {
        return Map.of("out_trade_no", body.path("out_trade_no").asText(), "trade_no", "ali-trade",
                "trade_status", status, "total_amount", amount);
    }
    long balance() { return db.queryForObject("SELECT balance_micros FROM billing_wallet", Long.class); }
    // Put test eligibility in the past: H2 rounds TIMESTAMP(3), which may round 'now' into the future.
    void due(String id) { db.update("UPDATE payment_order SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", id); }
    // H2 rounds TIMESTAMP(3); make a newly prepared refund eligible before testing dispatch/recovery.
    void prepareDueRefund(String orderId, String reason) {
        var service = new PaymentRefundService(db,
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())));
        var refund = service.begin(orderId, "admin", reason);
        db.update("UPDATE payment_refund SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", refund.id());
    }

    @Test void precreateSendsExactAmountAndPublicCallback() {
        var order = order();
        reply = (method, body) -> Map.of("out_trade_no", order.id(), "qr_code", "https://qr.alipay.com/real");
        assertThat(alipay.prepay(order)).isEqualTo("https://qr.alipay.com/real");
        var request = requests.getFirst();
        assertThat(request.get("method")).isEqualTo("alipay.trade.precreate");
        assertThat(request.get("notify_url")).isEqualTo("https://payments.example/api/payments/callback/alipay");
        assertThat(json.readTree(request.get("biz_content")).path("total_amount").asText()).isEqualTo("1.23");
    }

    @Test void developmentApplicationReportsUnavailableUntilItIsApproved() {
        reply = (method, body) -> Map.of("code", "40004", "sub_code", "isv.not-online-app");
        assertThatThrownBy(() -> alipay.prepay(order()))
                .isInstanceOfSatisfying(BillingException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(503);
                    assertThat(error.getReason()).contains("应用尚未上线", "isv.not-online-app");
                });
        assertThat(balance()).isZero();
    }

    @Test void missingCallbackIsRecoveredAndRepeatedQueriesCreditOnce() {
        var order = order();
        reply = (method, body) -> trade(body, "TRADE_SUCCESS", "1.23");
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("PAID");
        recovery.refresh("alice", order.id());
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        assertThat(balance()).isEqualTo(1230000L);
        assertThat(requests).hasSize(1);
    }

    @Test void forgedSignedResponseNeverCredits() {
        var order = order();
        reply = (method, body) -> trade(body, "TRADE_SUCCESS", "1.23");
        tamper = true;
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
    }

    @Test void wrongAmountOrOrderResponseNeverCredits() {
        var order = order();
        reply = (method, body) -> trade(body, "TRADE_SUCCESS", "9.99");
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("PENDING");
        due(order.id());
        reply = (method, body) -> Map.of("out_trade_no", "other-order", "trade_no", "ali-trade",
                "trade_status", "TRADE_SUCCESS", "total_amount", "1.23");
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
    }

    @Test void expiredWaitingOrderIsClosedOnlyAfterProviderAcknowledges() {
        var order = order();
        db.update("UPDATE payment_order SET expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP) WHERE id=?", order.id());
        reply = (method, body) -> method.endsWith("close")
                ? Map.of("out_trade_no", order.id(), "trade_no", "ali-trade")
                : trade(body, "WAIT_BUYER_PAY", "1.23");
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("CLOSED");
        assertThat(requests.stream().map(r -> r.get("method"))).containsExactly("alipay.trade.query", "alipay.trade.close");
        assertThat(balance()).isZero();
    }

    @Test void failedCloseDoesNotDiscardAnOrderOrLatePayment() {
        var order = order();
        db.update("UPDATE payment_order SET expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP) WHERE id=?", order.id());
        reply = (method, body) -> {
            if (method.endsWith("close")) throw new IllegalStateException("network failed");
            return trade(body, "WAIT_BUYER_PAY", "1.23");
        };
        assertThat(recovery.refresh("alice", order.id()).status()).isEqualTo("PENDING");
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        assertThat(balance()).isEqualTo(1230000L);
    }

    @Test void recoveryLeaseLimitsConcurrentPollingAndDoesNotExposeOthersOrders() throws Exception {
        var order = order();
        assertThatThrownBy(() -> recovery.refresh("bob", order.id())).isInstanceOf(BillingException.class);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> recovery.refresh("alice", order.id())));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(requests).hasSize(1);
    }

    @Test void fullRefundDeductsWalletOnceAndRepeatedCallbackCannotRecredit() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        reply = (method, body) -> method.endsWith("refund.query") ? Map.of()
                : Map.of("out_trade_no", order.id(), "trade_no", "ali-trade", "refund_fee", "1.23", "fund_change", "Y");
        prepareDueRefund(order.id(), "用户申请退款");
        var refund = recovery.refund(order.id(), "admin", "用户申请退款");
        assertThat(refund.status()).isEqualTo("SUCCEEDED");
        assertThat(recovery.refund(order.id(), "admin", "用户申请退款").id()).isEqualTo(refund.id());
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        assertThat(balance()).isZero();
        assertThat(orders.get("alice", order.id()).status()).isEqualTo("REFUNDED");
        assertThat(requests.stream().filter(r -> "alipay.trade.refund".equals(r.get("method")))).hasSize(1);
    }

    @Test void refundRejectsMoneyAlreadySpentOrReserved() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        db.update("UPDATE billing_wallet SET reserved_micros=1");
        assertThatThrownBy(() -> recovery.refund(order.id(), "admin", "余额不足测试")).isInstanceOf(BillingException.class);
        assertThat(balance()).isEqualTo(1230000L);
        assertThat(requests).isEmpty();
    }

    @Test void uncertainRefundKeepsFundsAndRecoversUsingSameRequestId() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        reply = (method, body) -> {
            if (method.endsWith("refund.query")) return Map.of();
            throw new IllegalStateException("timeout after provider accepted refund");
        };
        prepareDueRefund(order.id(), "用户申请退款");
        var refund = recovery.refund(order.id(), "admin", "用户申请退款");
        assertThat(refund.status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
        db.update("UPDATE payment_refund SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        reply = (method, body) -> Map.of("out_trade_no", order.id(), "trade_no", "ali-trade",
                "out_request_no", refund.id(), "refund_amount", "1.23", "refund_status", "REFUND_SUCCESS");
        recovery.runOnce();
        assertThat(orders.get("alice", order.id()).status()).isEqualTo("REFUNDED");
        assertThat(balance()).isZero();
        assertThat(requests.stream().filter(r -> "alipay.trade.refund".equals(r.get("method")))).hasSize(1);
    }

    @Test void negativeFundsChangeRequiresPositiveRefundQueryConfirmation() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        reply = (method, body) -> method.endsWith("refund.query") ? Map.of()
                : Map.of("out_trade_no", order.id(), "trade_no", "ali-trade", "refund_fee", "1.23", "fund_change", "N");
        assertThat(recovery.refund(order.id(), "admin", "退款结果待确认").status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
        assertThat(orders.get("alice", order.id()).status()).isEqualTo("REFUNDING");
    }

    @Test void lateRejectionCannotReleaseAnEarlierAmbiguousRefund() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        reply = (method, body) -> {
            if (method.endsWith("refund.query")) return Map.of();
            throw new IllegalStateException("accepted then connection lost");
        };
        prepareDueRefund(order.id(), "超时后重试");
        var refund = recovery.refund(order.id(), "admin", "超时后重试");
        db.update("UPDATE payment_refund SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        reply = (method, body) -> method.endsWith("refund.query") ? Map.of()
                : Map.of("code", "40004", "sub_code", "ACQ.SELLER_BALANCE_NOT_ENOUGH");
        recovery.runOnce();
        assertThat(balance()).isZero();
        assertThat(orders.get("alice",order.id()).status()).isEqualTo("REFUNDING");
        assertThat(db.queryForObject("SELECT status FROM payment_refund WHERE id=?",String.class,refund.id())).isEqualTo("PENDING");
        assertThat(requests.stream().filter(r -> "alipay.trade.refund".equals(r.get("method")))
                .map(r -> json.readTree(r.get("biz_content")).path("out_request_no").asText())).containsExactly(refund.id(),refund.id());
    }

    @Test void explicitFirstRefundRejectionRestoresWalletOnlyOnce() {
        var order = order();
        orders.credit(order.id(), "ALIPAY", "ali-trade", 123, "CNY");
        reply = (method, body) -> method.endsWith("refund.query") ? Map.of()
                : Map.of("code", "40004", "sub_code", "ACQ.SELLER_BALANCE_NOT_ENOUGH");
        prepareDueRefund(order.id(), "首次明确拒绝");
        assertThat(recovery.refund(order.id(),"admin","首次明确拒绝").status()).isEqualTo("FAILED");
        recovery.runOnce();
        assertThat(balance()).isEqualTo(1230000L);
        assertThat(orders.get("alice",order.id()).status()).isEqualTo("PAID");
    }

    @Test void staleCloseCannotOverwritePaidAndClosedCanReceiveLateVerifiedPayment() {
        var a = order();
        String owner = orders.acquireSync(a.id());
        orders.credit(a.id(),"ALIPAY","ali-trade",123,"CNY");
        orders.closed(a.id(),owner);
        assertThat(orders.get("alice",a.id()).status()).isEqualTo("PAID");
        var b = order();
        orders.closed(b.id(),orders.acquireSync(b.id()));
        orders.credit(b.id(),"ALIPAY","late-trade",123,"CNY");
        assertThat(balance()).isEqualTo(2460000L);
    }

    @Test void prepayAndRecoveryLeasesExcludeEachOther() {
        var a = orders.create("alice","prepay-lease","ALIPAY",123);
        String prepay = orders.acquirePrepay("alice",a.id());
        assertThat(orders.acquireSync(a.id())).isNull();
        orders.releasePrepay("alice",a.id(),prepay);
        assertThat(orders.acquireSync(a.id())).isNotNull();
        assertThatThrownBy(() -> orders.acquirePrepay("alice",a.id())).isInstanceOf(BillingException.class);
    }

    @Test void forgedErrorResponseCannotReleaseRefundFunds() {
        var order = order();
        orders.credit(order.id(),"ALIPAY","ali-trade",123,"CNY");
        reply = (method, body) -> {
            if (method.endsWith("refund.query")) return Map.of();
            tamper = true;
            return Map.of("code","40004","sub_code","ACQ.SELLER_BALANCE_NOT_ENOUGH");
        };
        assertThat(recovery.refund(order.id(),"admin","拒绝响应也必须验签").status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
    }

    @Test void refundAndReservationRaceNeverOverspendsWallet() throws Exception {
        var order = order();
        orders.credit(order.id(),"ALIPAY","ali-trade",123,"CNY");
        reply = (method, body) -> method.endsWith("refund.query") ? Map.of()
                : Map.of("out_trade_no",order.id(),"trade_no","ali-trade","refund_fee","1.23","fund_change","Y");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reservation = pool.submit(() -> db.update("UPDATE billing_wallet SET reserved_micros=reserved_micros+1000000 WHERE user_id='alice' AND balance_micros-reserved_micros>=1000000"));
            var refund = pool.submit(() -> {
                try { recovery.refund(order.id(),"admin","并发余额测试"); }
                catch (BillingException insufficient) { assertThat(insufficient.getStatusCode().value()).isEqualTo(409); }
            });
            reservation.get(15,TimeUnit.SECONDS);
            refund.get(15,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT balance_micros-reserved_micros FROM billing_wallet",Long.class)).isGreaterThanOrEqualTo(0);
    }

    @Test void unsignedRefundErrorCannotRestoreSpendableFunds() {
        unsignedRefundErrorCannotRestoreSpendableFunds(false);
    }

    @Test void blankSignatureRefundErrorCannotRestoreSpendableFunds() {
        unsignedRefundErrorCannotRestoreSpendableFunds(true);
    }

    private void unsignedRefundErrorCannotRestoreSpendableFunds(boolean blank) {
        var order = order();
        orders.credit(order.id(),"ALIPAY","ali-trade",123,"CNY");
        reply = (method, body) -> {
            if (method.endsWith("refund.query")) return Map.of();
            omitSignature = !blank;
            blankSignature = blank;
            return Map.of("code","40004","sub_code","ACQ.SELLER_BALANCE_NOT_ENOUGH");
        };
        assertThat(recovery.refund(order.id(),"admin","未签名拒绝不能作为资金依据").status()).isEqualTo("PENDING");
        assertThat(balance()).isZero();
        assertThat(orders.get("alice",order.id()).status()).isEqualTo("REFUNDING");
    }

    @Test void expiredPrepayLeaseCannotPublishQrCode() {
        var order = orders.create("alice","stale-prepay","ALIPAY",123);
        String lease = orders.acquirePrepay("alice",order.id());
        db.update("UPDATE payment_order SET prepay_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order.id());
        orders.qr("alice",order.id(),lease,"https://qr.alipay.com/stale");
        assertThat(orders.get("alice",order.id()).qrCode()).isNull();
    }
}
