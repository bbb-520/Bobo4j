package com.bbb.exercise.agentdemo.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({McpProperties.class,McpPublicDocumentation.class})
public class McpConfiguration {
    @Bean public McpConnectionManager mcpConnectionManager(McpProperties properties,McpPublicDocumentation publicDocs,MeterRegistry metrics) {
        return new McpConnectionManager(publicDocs.apply(properties),metrics);
    }
}
