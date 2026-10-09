package com.bbb.exercise.agentdemo.common.observability;

import io.micrometer.common.KeyValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.*;
import java.util.Set;

/** Metric labels use resource templates; trace URLs and actual signed requests remain exact. */
@Configuration(proxyBeanMethods=false)
public class ServiceClientObservations {
    private static final Set<String> RESOURCES=Set.of("users","groups","attempts","calls","documents","sources","assets","image-jobs","conversations","agent-executions","agent-runs","generations");
    @Bean @ConditionalOnMissingBean(ClientRequestObservationConvention.class)
    public ClientRequestObservationConvention serviceClientConvention() {
        return new DefaultClientRequestObservationConvention() {
            @Override protected KeyValue uri(ClientRequestObservationContext context) {
                KeyValue original=super.uri(context);String template=original.getValue();
                if(!template.startsWith("/internal/"))return original;
                String[] parts=template.split("\\?",2)[0].split("/",-1);
                for(int i=1;i<parts.length;i++)if(RESOURCES.contains(parts[i-1]))parts[i]="{id}";
                return KeyValue.of("uri",String.join("/",parts));
            }
        };
    }
}
