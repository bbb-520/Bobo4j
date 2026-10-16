package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;

/** Apply the same offline schema policy to SDK validation; the SDK must never resolve remote refs. */
final class McpSchemaValidator implements JsonSchemaValidator {
    @Override public ValidationResponse validateSchema(Map<String,Object> schema) {
        try {new ToolSchema(ToolSchema.canonical(schema),false);return ValidationResponse.asValid("{}");}
        catch(Exception error){return ValidationResponse.asInvalid("MCP schema is not an approved offline object schema");}
    }
    @Override @SuppressWarnings("unchecked") public ValidationResponse validate(Map<String,Object> schema,Object value) {
        try {
            if(!(value instanceof Map<?,?>))return ValidationResponse.asInvalid("MCP result must match its object schema");
            new ToolSchema(ToolSchema.canonical(schema),false).validate((Map<String,Object>)value);
            return ValidationResponse.asValid(ToolSchema.canonical(value));
        } catch(Exception error){return ValidationResponse.asInvalid("MCP result failed offline schema validation");}
    }
}
