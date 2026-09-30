package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo.api.AuthInternalApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.mediaservice",
        "com.bbb.exercise.agentdemo1_0",
        "com.bbb.exercise.agentdemo.common"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo1_0")
@EnableDiscoveryClient
@EnableFeignClients(clients = AuthInternalApi.class)
@EnableScheduling
public class MediaServiceApplication {
    public static void main(String[] args) { SpringApplication.run(MediaServiceApplication.class, args); }
}
