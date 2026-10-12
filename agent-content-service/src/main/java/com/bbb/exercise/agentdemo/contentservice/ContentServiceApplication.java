package com.bbb.exercise.agentdemo.contentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 内容服务启动入口，装配作品发布和 Zine 生成业务及共享运行时。 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bbb.exercise.agentdemo.runtime.security",
            "com.bbb.exercise.agentdemo.contentservice",
            "com.bbb.exercise.agentdemo.contentservice.bobo",
            "com.bbb.exercise.agentdemo.runtime.storage",
            "com.bbb.exercise.agentdemo.runtime.client",
            "com.bbb.exercise.agentdemo.runtime.config",
            "com.bbb.exercise.agentdemo.runtime.identity",
            "com.bbb.exercise.agentdemo.contentservice.zine",
            "com.bbb.exercise.agentdemo.common"
        })
@ConfigurationPropertiesScan(
        basePackages = {
            "com.bbb.exercise.agentdemo.runtime.config",
            "com.bbb.exercise.agentdemo.contentservice"
        })
@EnableDiscoveryClient
@EnableScheduling
public class ContentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
