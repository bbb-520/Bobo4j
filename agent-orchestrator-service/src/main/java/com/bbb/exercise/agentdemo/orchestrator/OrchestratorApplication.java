package com.bbb.exercise.agentdemo.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.orchestrator",
        // 共享 CORS 策略的显式落点。2026-10-01 从 com.bbb.exercise.agentdemo.chatservice.config.WebConfig
        // 迁到 com.bbb.exercise.agentdemo.runtime.web；这里补扫描是为了保持响应头不变。
        // 只扫 .web 而不扫整个 .common：common 下的 client 包是 Auth/Internal 客户端 Bean，
        // 本服务不需要，扩大扫描会凭空多出三个 Bean。
        "com.bbb.exercise.agentdemo.runtime.web"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo.orchestrator")
@EnableDiscoveryClient
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
