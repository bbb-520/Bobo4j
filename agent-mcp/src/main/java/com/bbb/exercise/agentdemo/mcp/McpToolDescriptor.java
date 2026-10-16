package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;

/** Reviewed client metadata only; execution plans and ledger scope belong to the caller. */
public record McpToolDescriptor(String alias,String description,ToolSchema input,McpCall call) {}
