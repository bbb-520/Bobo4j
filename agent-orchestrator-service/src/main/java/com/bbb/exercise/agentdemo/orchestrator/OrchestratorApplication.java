package com.bbb.exercise.agentdemo.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.orchestrator",
        "com.bbb.exercise.agentdemo1_0"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo1_0")
@EnableDiscoveryClient
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
