package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Settlement;
import com.bbb.exercise.agentdemo.runtime.client.BillingClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

/** Preserve synchronous generation results so a lost settlement response can be recovered by the owner. */
@Service
public class ZineResultService {
    private final JdbcTemplate jdbc;private final BillingClient billing;private final JsonMapper json=JsonMapper.builder().build();
    public ZineResultService(JdbcTemplate jdbc,BillingClient billing) {this.jdbc=jdbc;this.billing=billing;}
    public Mono<Result> generate(ChatIdentity identity,String id,String model,Mono<ZineGenerationService.ZineGenerationResponse> provider) {
        return billing.image(identity,id,model,() -> provider.flatMap(response -> Mono.fromCallable(() -> {
            jdbc.update("INSERT INTO zine_generation_receipt(id,tenant_id,user_id,response_json) VALUES (?,?,?,?)",id,identity.tenantId(),identity.userId(),json.writeValueAsString(response));return response;
        }).subscribeOn(Schedulers.boundedElastic())),response -> new Settlement(response.inputTokens(),response.outputTokens(),response.providerRequestId()))
                .map(response -> new Result(id,response));
    }
    public Mono<Result> get(ChatIdentity identity,String id) {
        return Mono.fromCallable(() -> {
            var rows=jdbc.query("SELECT response_json FROM zine_generation_receipt WHERE id=? AND tenant_id=? AND user_id=?",(rs,n)->json.readValue(rs.getString(1),ZineGenerationService.ZineGenerationResponse.class),id,identity.tenantId(),identity.userId());
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"生成结果不存在");return rows.getFirst();
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(response -> billing.settle(identity,id,new Settlement(response.inputTokens(),response.outputTokens(),response.providerRequestId())).thenReturn(new Result(id,response)));
    }
    public Mono<List<String>> list(ChatIdentity identity) {
        return Mono.fromCallable(() -> jdbc.query("SELECT id FROM zine_generation_receipt WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT 50",(rs,n)->rs.getString(1),identity.tenantId(),identity.userId())).subscribeOn(Schedulers.boundedElastic());
    }
    public record Result(String requestId,ZineGenerationService.ZineGenerationResponse result) {}
}
