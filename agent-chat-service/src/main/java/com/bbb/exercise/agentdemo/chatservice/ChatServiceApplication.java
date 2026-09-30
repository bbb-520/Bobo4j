package com.bbb.exercise.agentdemo.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.chatservice",
        "com.bbb.exercise.agentdemo1_0",
        "com.bbb.exercise.agentdemo.common"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo1_0")
@EnableDiscoveryClient
public class ChatServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ChatServiceApplication.class, args); }
}
