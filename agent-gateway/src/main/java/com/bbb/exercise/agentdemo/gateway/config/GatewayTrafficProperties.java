package com.bbb.exercise.agentdemo.gateway.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.*;
@ConfigurationProperties("app.traffic")
public class GatewayTrafficProperties {
    private double routeQps=50;
    private long intervalSeconds=1;
    private Map<String,Double> routes=new LinkedHashMap<>();
    public double getRouteQps(){return routeQps;}
    public void setRouteQps(double value){routeQps=value;}
    public long getIntervalSeconds(){return intervalSeconds;}
    public void setIntervalSeconds(long value){intervalSeconds=value;}
    public Map<String,Double> getRoutes(){return routes;}
    public void setRoutes(Map<String,Double> value){routes=new LinkedHashMap<>(value);}
}
