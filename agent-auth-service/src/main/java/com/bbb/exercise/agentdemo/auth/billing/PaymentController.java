package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.payment.PaymentGateway;
import com.bbb.exercise.agentdemo.auth.payment.PaymentReconciliation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.HashMap;

/** 充值支付入口，提供订单创建和查询并接收支付平台的服务端回调。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final AuthService auth;
    private final AuthIdentityResolver identities;
    private final PaymentService orders;
    private final PaymentGateway gateway;
    private final PaymentReconciliation recovery;

    public PaymentController(
            AuthService auth,
            AuthIdentityResolver identities,
            PaymentService orders,
            PaymentGateway gateway,
            PaymentReconciliation recovery) {
        this.auth = auth;
        this.identities = identities;
        this.orders = orders;
        this.gateway = gateway;
        this.recovery = recovery;
    }

    // 1. 充值入口：创建幂等订单，并在取得预下单租约后向渠道申请二维码。
    @PostMapping("/orders")
    public Mono<PaymentService.Order> create(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateOrder body,
            ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                                () -> {
                                                    gateway.requireEnabled(body.channel());
                                                    String user = auth.publicUserId(identity);
                                                    var order =
                                                            orders.create(
                                                                    user,
                                                                    key,
                                                                    body.channel(),
                                                                    body.amountCents());

                                                    if (order.qrCode() != null
                                                            || !"PENDING".equals(order.status())) {
                                                        return order;
                                                    }

                                                    String lease =
                                                            orders.acquirePrepay(user, order.id());

                                                    try {
                                                        orders.qr(
                                                                user,
                                                                order.id(),
                                                                lease,
                                                                gateway.prepay(order));
                                                        return orders.get(user, order.id());
                                                    } catch(RuntimeException failure) {
                                                        orders.prepayFailed(user,order.id(),lease,failure);throw failure;
                                                    } finally {
                                                        orders.releasePrepay(
                                                                user, order.id(), lease);
                                                    }
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // 1.1. 充值状态：根据当前账户查询已有订单，不以浏览器跳转作为支付成功依据。
    @GetMapping("/orders/{id}")
    public Mono<PaymentService.Order> get(@PathVariable String id, ServerWebExchange exchange) {
        return identities
                .resolveRequired(exchange)
                .flatMap(
                        i ->
                                Mono.fromCallable(() -> recovery.refresh(auth.publicUserId(i), id))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** Refresh the same owned order; old QR is retained until a new response is safely persisted. */
    @PostMapping("/orders/{id}/qr")
    public Mono<PaymentService.Order> refreshQr(@PathVariable String id,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromCallable(() -> {
            String user=auth.publicUserId(identity);
            var order=orders.get(user,id);
            gateway.requireEnabled(order.channel());
            String lease=orders.acquirePrepay(user,id,true);
            try {
                orders.qr(user,id,lease,gateway.prepay(order));
                return orders.get(user,id);
            } catch(RuntimeException failure) { orders.prepayFailed(user,id,lease,failure);throw failure; }
            finally { orders.releasePrepay(user,id,lease); }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // 2. 支付宝回调：交由渠道网关验签和核对交易信息，再执行入账。
    @PostMapping(
            value = "/callback/alipay",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> alipay(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(
                        form ->
                                Mono.fromCallable(
                                                () -> {
                                                    var fields = new HashMap<String, String>();

                                                    form.forEach(
                                                            (key, values) -> {
                                                                if (values.size() != 1) {
                                                                    throw new BillingException(
                                                                            400, "重复支付参数");
                                                                }

                                                                fields.put(key, values.getFirst());
                                                            });
                                                    gateway.alipayNotify(fields);

                                                    return "success";
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** 充值订单创建请求，指定支付渠道及以分为单位的充值金额。 */
    public record CreateOrder(String channel, long amountCents) {}
}
