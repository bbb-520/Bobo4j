package com.bbb.exercise.agentdemo.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import com.bbb.exercise.agentdemo.api.model.ModelProviderRegistry;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.authservice",
        // 共享 CORS 策略的显式落点。2026-10-01 从 com.bbb.exercise.agentdemo.chatservice.config.WebConfig
        // 迁到 com.bbb.exercise.agentdemo.runtime.web；这里补扫描是为了保持响应头不变。
        "com.bbb.exercise.agentdemo.runtime.web",
        "com.bbb.exercise.agentdemo.auth",
        "com.bbb.exercise.agentdemo.runtime.config",
        "com.bbb.exercise.agentdemo.auth.identity",
        "com.bbb.exercise.agentdemo.auth.model"
})
@ConfigurationPropertiesScan(basePackages = {"com.bbb.exercise.agentdemo.runtime.config", "com.bbb.exercise.agentdemo.auth.payment"})
@EnableDiscoveryClient
@org.springframework.scheduling.annotation.EnableScheduling
@MapperScan({"com.bbb.exercise.agentdemo.auth.mapper", "com.bbb.exercise.agentdemo.auth.model"})
public class AuthServiceApplication {
    @Bean
    ModelProviderRegistry modelProviderRegistry() { return ModelProviderRegistry.defaultRegistry(); }

    public static void main(String[] args) { SpringApplication.run(AuthServiceApplication.class, args); }
}
