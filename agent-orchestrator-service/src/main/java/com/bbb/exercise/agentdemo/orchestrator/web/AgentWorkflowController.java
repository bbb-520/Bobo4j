package com.bbb.exercise.agentdemo.orchestrator.web;

import com.bbb.exercise.agentdemo.common.api.ApiResponse;
import com.bbb.exercise.agentdemo.orchestrator.auth.AuthSessionClient;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/** 工作流 HTTP 入口，在会话认证后创建、查询和推进所属用户的运行记录。 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentWorkflowController {
    private final AgentWorkflowService workflows;
    private final AuthSessionClient sessions;

    public AgentWorkflowController(AgentWorkflowService workflows, AuthSessionClient sessions) {
        this.workflows = workflows;
        this.sessions = sessions;
    }

    // 1. 工作流入口：解析认证用户后创建 REVISE 状态记录。
    @PostMapping
    public Mono<ApiResponse<AgentRun>> start(
            @RequestParam(name = "workflow", defaultValue = "REVISE") String workflow,
            @RequestParam(name = "input") String input,
            ServerWebExchange exchange) {
        return sessions.requireUser(exchange)
                .flatMap(
                        userId ->
                                Mono.fromCallable(
                                                () ->
                                                        ApiResponse.success(
                                                                workflows.start(
                                                                        workflow, input, userId)))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // 2. 推进入口：按当前用户身份提交目标状态。
    @PostMapping("/{id}/advance")
    public Mono<ApiResponse<AgentRun>> advance(
            @PathVariable UUID id,
            @RequestParam(name = "status") RunStatus status,
            ServerWebExchange exchange) {
        return sessions.requireUser(exchange)
                .flatMap(
                        userId ->
                                Mono.fromCallable(
                                                () -> {
                                                    if (workflows.get(id, userId) == null) {
                                                        throw new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND);
                                                    }

                                                    return ApiResponse.success(
                                                            workflows.advance(id, status, userId));
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // 3. 查询入口：复用服务层的所有者隔离。
    @GetMapping("/{id}")
    public Mono<ApiResponse<AgentRun>> get(@PathVariable UUID id, ServerWebExchange exchange) {
        return sessions.requireUser(exchange)
                .flatMap(
                        userId ->
                                Mono.fromCallable(
                                                () -> {
                                                    AgentRun run = workflows.get(id, userId);

                                                    if (run == null) {
                                                        throw new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "AgentRun 不存在");
                                                    }

                                                    return ApiResponse.success(run);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
