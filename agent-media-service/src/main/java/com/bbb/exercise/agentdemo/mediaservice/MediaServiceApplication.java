package com.bbb.exercise.agentdemo.mediaservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 媒体服务启动入口，装配图片资产、生成任务和供应商适配器。 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bbb.exercise.agentdemo.runtime.security",
            "com.bbb.exercise.agentdemo.mediaservice",
            "com.bbb.exercise.agentdemo.mediaservice.image",
            "com.bbb.exercise.agentdemo.runtime.storage",
            "com.bbb.exercise.agentdemo.runtime.client",
            "com.bbb.exercise.agentdemo.runtime.config",
            "com.bbb.exercise.agentdemo.runtime.identity",
            "com.bbb.exercise.agentdemo.mediaservice.provider",
            "com.bbb.exercise.agentdemo.mediaservice.config",
            "com.bbb.exercise.agentdemo.common"
        })
@ConfigurationPropertiesScan(
        basePackages = {
            "com.bbb.exercise.agentdemo.runtime.config",
            "com.bbb.exercise.agentdemo.mediaservice.config"
        })
@EnableDiscoveryClient
@EnableScheduling
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
