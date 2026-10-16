package com.bbb.exercise.agentdemo.orchestrator.mcp;

import com.bbb.exercise.agentdemo.mcp.McpConnectionManager;
import com.bbb.exercise.agentdemo.orchestrator.execution.ExecutionToolRegistry;
import org.springframework.stereotype.Component;
import java.util.List;

/** Adapts reviewed client descriptors into plans; remote IO remains in the execution ledger. */
@Component
public final class McpToolSourceAdapter implements ExecutionToolRegistry.ToolSource {
    private final McpConnectionManager connections;
    public McpToolSourceAdapter(McpConnectionManager connections){this.connections=connections;}
    @Override public List<ExecutionToolRegistry.Registration> registrations(ExecutionToolRegistry.Scope scope) {
        return connections.tools(scope.identity()).stream().map(tool->new ExecutionToolRegistry.Registration(
                tool.alias(),tool.description(),tool.input(),(arguments,who)->ExecutionToolRegistry.Plan.mcp(tool.call(),arguments))).toList();
    }
}
