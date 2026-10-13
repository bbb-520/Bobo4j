package com.bbb.exercise.agentdemo.gateway.config;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.*;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.*;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.http.MediaType;
import java.util.*;

/** Concrete Sentinel route rules; limits are per gateway instance and can be overridden by dashboard rules. */
@Configuration
@EnableConfigurationProperties(GatewayTrafficProperties.class)
@ConditionalOnProperty(name="app.traffic.enabled",havingValue="true",matchIfMissing=true)
public class GatewayTrafficConfiguration {
    private final GatewayTrafficProperties properties;private final MeterRegistry metrics;private final ObservationRegistry observations;
    public GatewayTrafficConfiguration(GatewayTrafficProperties properties,MeterRegistry metrics,ObservationRegistry observations){this.properties=properties;this.metrics=metrics;this.observations=observations;}
    @PostConstruct public void install() {
        if(!Double.isFinite(properties.getRouteQps())||properties.getRouteQps()<=0||properties.getIntervalSeconds()<1||properties.getIntervalSeconds()>60)throw new IllegalArgumentException("网关限流配置无效");
        var routes=new LinkedHashMap<String,Double>();
        for(String route:List.of("auth","chat","images","content","orchestrator","documents"))routes.put(route,properties.getRouteQps());
        routes.putAll(properties.getRoutes());var rules=new HashSet<GatewayFlowRule>();
        routes.forEach((route,qps)->{if(!Double.isFinite(qps)||qps<=0)throw new IllegalArgumentException("网关路由额度无效");rules.add(new GatewayFlowRule(route).setCount(qps*properties.getIntervalSeconds()).setIntervalSec(properties.getIntervalSeconds()));});
        // ParamFlowSlot initializes its manager lazily and clears parameter metrics once.
        // Initialize it before gateway rules so the first request is counted as well.
        com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager.getRules();
        GatewayRuleManager.loadRules(rules);
        GatewayCallbackManager.setBlockHandler((exchange,error)->{
            Route selected=exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);String route=selected==null?"unknown":selected.getId();
            metrics.counter("gateway.traffic.blocked","route",route).increment();
            String requestId=exchange.getResponse().getHeaders().getFirst("X-Request-Id");
            var observation=Observation.createNotStarted("gateway.traffic.blocked",observations).lowCardinalityKeyValue("route",route).highCardinalityKeyValue("request.id",requestId==null?"unknown":requestId).start();
            return ServerResponse.status(429).contentType(MediaType.APPLICATION_JSON).header("Retry-After",String.valueOf(properties.getIntervalSeconds())).bodyValue(Map.of("code","RATE_LIMITED","message","请求过于频繁，请稍后重试","requestId",requestId==null?"":requestId)).doOnError(observation::error).doFinally(signal->observation.stop());
        });
    }
}
