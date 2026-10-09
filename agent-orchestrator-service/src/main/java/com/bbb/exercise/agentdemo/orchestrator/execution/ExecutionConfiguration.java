package com.bbb.exercise.agentdemo.orchestrator.execution;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration
@EnableScheduling
public class ExecutionConfiguration {
    @Bean PrincipalKeyRing executionPrincipalKeys(@Value("${app.security.internal-principal-secrets:}") String secrets,
        @Value("${app.security.internal-principal-active-key-id:current}") String active) {return new PrincipalKeyRing(secrets,active);}
}
