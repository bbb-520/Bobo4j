package com.bbb.exercise.agentdemo.orchestrator.mcp;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface McpToolExecutor {
    CompletableFuture<Map<String,Object>> execute(ChatIdentity identity,McpCall call,Map<String,Object> arguments);
}
