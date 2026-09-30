package com.bbb.exercise.agentdemo.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(scanBasePackages = {
        "com.bbb.exercise.agentdemo.authservice",
        "com.bbb.exercise.agentdemo1_0"
})
@ConfigurationPropertiesScan(basePackages = "com.bbb.exercise.agentdemo1_0")
@EnableDiscoveryClient
@MapperScan("com.bbb.exercise.agentdemo1_0.auth.mapper")
public class AuthServiceApplication {
    public static void main(String[] args) { SpringApplication.run(AuthServiceApplication.class, args); }
}
