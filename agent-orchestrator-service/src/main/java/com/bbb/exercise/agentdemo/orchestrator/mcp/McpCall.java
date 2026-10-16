package com.bbb.exercise.agentdemo.orchestrator.mcp;

/** Operator-selected target and schema identity; contains no URL or credential. */
public record McpCall(String serverId,String tool,String schemaHash) {
    public McpCall {
        if(serverId==null||!serverId.matches("[a-z][a-z0-9-]{0,31}")||tool==null||!tool.matches("[A-Za-z0-9_.-]{1,128}")
                ||schemaHash==null||!schemaHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid MCP target");
    }
}
