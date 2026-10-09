package com.bbb.exercise.agentdemo.ragservice;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import com.bbb.exercise.agentdemo.api.model.ModelProviderRegistry;
@SpringBootApplication(scanBasePackages={"com.bbb.exercise.agentdemo.ragservice","com.bbb.exercise.agentdemo.runtime.client","com.bbb.exercise.agentdemo.runtime.identity","com.bbb.exercise.agentdemo.common"})
@ConfigurationPropertiesScan("com.bbb.exercise.agentdemo.runtime.config")
@EnableScheduling @EnableDiscoveryClient
public class RagServiceApplication {
    @Bean ModelProviderRegistry modelProviderRegistry(){return ModelProviderRegistry.defaultRegistry();}
    public static void main(String[] args){SpringApplication.run(RagServiceApplication.class,args);}
}
