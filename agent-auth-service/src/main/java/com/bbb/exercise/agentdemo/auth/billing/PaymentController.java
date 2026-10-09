package com.bbb.exercise.agentdemo.auth.billing;

import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.identity.AuthIdentityResolver;
import com.bbb.exercise.agentdemo.auth.payment.PaymentGateway;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.HashMap;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final AuthService auth;private final AuthIdentityResolver identities;private final PaymentService orders;private final PaymentGateway gateway;
    public PaymentController(AuthService auth,AuthIdentityResolver identities,PaymentService orders,PaymentGateway gateway) {this.auth=auth;this.identities=identities;this.orders=orders;this.gateway=gateway;}
    @PostMapping("/orders") public Mono<PaymentService.Order> create(@RequestHeader("Idempotency-Key") String key,@RequestBody CreateOrder body,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(identity -> Mono.fromCallable(() -> {
            gateway.requireEnabled(body.channel());String user=auth.publicUserId(identity);var order=orders.create(user,key,body.channel(),body.amountCents());
            if(order.qrCode()!=null||!"PENDING".equals(order.status())) return order;
            String lease=orders.acquirePrepay(user,order.id());
            try {orders.qr(user,order.id(),gateway.prepay(order));return orders.get(user,order.id());}
            finally {orders.releasePrepay(user,order.id(),lease);}
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping("/orders/{id}") public Mono<PaymentService.Order> get(@PathVariable String id,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(i -> Mono.fromCallable(() -> orders.get(auth.publicUserId(i),id)).subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping(value="/callback/alipay",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> alipay(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> Mono.fromCallable(() -> {
            var fields=new HashMap<String,String>();form.forEach((key,values) -> {if(values.size()!=1) throw new BillingException(400,"重复支付参数");fields.put(key,values.getFirst());});
            gateway.alipayNotify(fields);return "success";
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping(value="/callback/wechat",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> wechat(@RequestBody String raw,ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {gateway.wechatNotify(exchange.getRequest().getHeaders(),raw);return ResponseEntity.noContent().<Void>build();}).subscribeOn(Schedulers.boundedElastic());
    }
    public record CreateOrder(String channel,long amountCents) {}
}
