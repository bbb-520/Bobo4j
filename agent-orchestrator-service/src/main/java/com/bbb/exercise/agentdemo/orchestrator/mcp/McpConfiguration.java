package com.bbb.exercise.agentdemo.orchestrator.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {
    @Bean public McpConnectionManager mcpConnectionManager(McpProperties properties,MeterRegistry metrics) {
        return new McpConnectionManager(properties,metrics);
    }
}
