package com.bbb.exercise.agentdemo.orchestrator.execution;

/** A local request ID cannot prove the outcome of a remote MCP operation. */
public final class McpDispatchUncertain extends RuntimeException {
    public McpDispatchUncertain(){super("外部工具结果尚未确认，已保留步骤且不会自动重发");}
}
