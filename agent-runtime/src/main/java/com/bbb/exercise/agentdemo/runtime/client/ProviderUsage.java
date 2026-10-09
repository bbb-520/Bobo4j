package com.bbb.exercise.agentdemo.runtime.client;

import tools.jackson.databind.JsonNode;

/** Never substitute image counts/pixel dimensions for tokens. */
public final class ProviderUsage {
    private ProviderUsage() {}
    public static Long input(JsonNode root) {return token(root,"input_tokens","prompt_tokens");}
    public static Long output(JsonNode root) {return token(root,"output_tokens","completion_tokens");}
    private static Long token(JsonNode root,String primary,String alternate) {
        JsonNode value=root.path("usage").path(primary);
        if(value.isMissingNode()) value=root.path("usage").path(alternate);
        if(!value.isIntegralNumber()||value.asLong(-1)<0) return null;return value.asLong();
    }
}
