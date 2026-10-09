package com.bbb.exercise.agentdemo.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import com.bbb.exercise.agentdemo.api.model.ModelProviderRegistry;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.chatservice",
        "com.bbb.exercise.agentdemo.chatservice.chat",
        "com.bbb.exercise.agentdemo.chatservice.config",
        "com.bbb.exercise.agentdemo.chatservice.conversation",
        "com.bbb.exercise.agentdemo.chatservice.enums",
        "com.bbb.exercise.agentdemo.chatservice.generation",
        "com.bbb.exercise.agentdemo.chatservice.memory",
        "com.bbb.exercise.agentdemo.chatservice.observability",
        "com.bbb.exercise.agentdemo.api.dto",
        "com.bbb.exercise.agentdemo.runtime.client",
        "com.bbb.exercise.agentdemo.runtime.identity",
        "com.bbb.exercise.agentdemo.chatservice.utils",
        "com.bbb.exercise.agentdemo.common"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo.runtime.config")
@EnableDiscoveryClient
public class ChatServiceApplication {
    @Bean
    ModelProviderRegistry modelProviderRegistry() { return ModelProviderRegistry.defaultRegistry(); }

    public static void main(String[] args) { SpringApplication.run(ChatServiceApplication.class, args); }
}
