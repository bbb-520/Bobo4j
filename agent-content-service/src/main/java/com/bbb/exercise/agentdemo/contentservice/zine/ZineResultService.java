package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Settlement;
import com.bbb.exercise.agentdemo.runtime.client.BillingClient;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.stream.Stream;

/** New work belongs to Media. Old Content receipts retain owner-scoped settlement recovery. */
@Service
public class ZineResultService {
    private final JdbcTemplate jdbc;
    private final BillingClient billing;
    private final InternalServiceClient media;
    private final JsonMapper json = JsonMapper.builder().build();
    public ZineResultService(JdbcTemplate jdbc, BillingClient billing, InternalServiceClient media) {
        this.jdbc=jdbc; this.billing=billing; this.media=media;
    }
    public Mono<Result> generate(ChatIdentity identity, ZineRequest request) {
        return media.generateZine(identity.userId(),identity.tenantId(),request).map(ZineResultService::result);
    }
    public Mono<Result> get(ChatIdentity identity, String id) {
        return Mono.fromCallable(() -> jdbc.query(
                "SELECT response_json FROM zine_generation_receipt WHERE id=? AND tenant_id=? AND user_id=?",
                (rs,n) -> json.readValue(rs.getString(1),ZineGenerationService.ZineGenerationResponse.class),
                id,identity.tenantId(),identity.userId()))
                .subscribeOn(Schedulers.boundedElastic()).flatMap(rows -> {
                    if (!rows.isEmpty()) {
                        var response=rows.getFirst();
                        return billing.settle(identity,id,new Settlement(response.inputTokens(),response.outputTokens(),response.providerRequestId()))
                                .thenReturn(new Result(id,response));
                    }
                    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"生成结果不存在"));
                    return media.getMedia("/internal/media/zine/generations/"+id,identity.userId(),identity.tenantId(),ZineResult.class)
                            .map(ZineResultService::result);
                });
    }
    public Mono<List<String>> list(ChatIdentity identity) {
        var historical = Mono.fromCallable(() -> jdbc.query(
                "SELECT id FROM zine_generation_receipt WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT 50",
                (rs,n) -> rs.getString(1),identity.tenantId(),identity.userId())).subscribeOn(Schedulers.boundedElastic());
        var current = media.getMedia("/internal/media/zine/generations",identity.userId(),identity.tenantId(),ZineRequests.class);
        return Mono.zip(current,historical,(recent,old) -> Stream.concat(recent.requestIds().stream(),old.stream()).distinct().limit(50).toList());
    }
    private static Result result(ZineResult remote) {
        var r=remote.result();
        return new Result(remote.requestId(),new ZineGenerationService.ZineGenerationResponse(r.mode(),r.model(),r.imageUrl(),r.rationale(),r.providerRequestId(),r.inputTokens(),r.outputTokens()));
    }
    public record Result(String requestId, ZineGenerationService.ZineGenerationResponse result) {}
}
