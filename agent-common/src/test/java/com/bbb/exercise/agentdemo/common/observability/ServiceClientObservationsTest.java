package com.bbb.exercise.agentdemo.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.*;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 验证跨服务指标使用稳定 URI 标签，同时在追踪中保留真实请求地址。 */
class ServiceClientObservationsTest {
    @Test
    void hundredsOfBillingAttemptsShareOneMetricUriWhileTraceKeepsActualUrl() {
        var convention = new ServiceClientObservations().serviceClientConvention();
        var metricUris = new HashSet<String>();

        for (int i = 0; i < 400; i++) {
            String path =
                    "/internal/billing/users/user-"
                            + i
                            + "/groups/call-"
                            + i
                            + "/attempts/attempt-"
                            + i
                            + "?cursor="
                            + i;
            var builder = ClientRequest.create(HttpMethod.GET, URI.create("http://auth" + path));
            var context = new ClientRequestObservationContext(builder);

            context.setRequest(builder.build());
            context.setUriTemplate(i % 2 == 0 ? path : "http://auth" + path);
            convention
                    .getLowCardinalityKeyValues(context)
                    .forEach(
                            k -> {
                                if (k.getKey().equals("uri")) {
                                    metricUris.add(k.getValue());
                                }
                            });
            assertThat(convention.getHighCardinalityKeyValues(context).toString())
                    .contains("call-" + i);
        }

        assertThat(metricUris)
                .containsExactly("/internal/billing/users/{id}/groups/{id}/attempts/{id}");
    }
}
