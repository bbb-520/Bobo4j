package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/** 编排服务启动入口，装配工作流状态管理、持久化执行及后台工作节点。 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bbb.exercise.agentdemo.runtime.security",
            "com.bbb.exercise.agentdemo.orchestrator",
            // 共享 CORS 策略的显式落点。2026-10-01 从 com.bbb.exercise.agentdemo.chatservice.config.WebConfig
            // 迁到 com.bbb.exercise.agentdemo.runtime.web；这里补扫描是为了保持响应头不变。
            // 浏览器认证所需的内部客户端通过显式导入装配，保持组件扫描范围。
            "com.bbb.exercise.agentdemo.runtime.web"
        })
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo.orchestrator")
@EnableDiscoveryClient
@Import(InternalServiceClient.class)
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
