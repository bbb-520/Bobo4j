package com.bbb.exercise.agentdemo.api.model;
import java.nio.charset.StandardCharsets;
import java.util.List;
/** Conservative byte upper bounds, never reported as measured provider token usage. */
public final class TokenBudgetEstimator {
    private TokenBudgetEstimator() {}
    public static final int MAX_COMPLETION_OUTPUT=2048;
    public static long completion(String mode,String system,String user,boolean fallback){return completion(ModelPrompts.forMode(mode,fallback)+"\n"+(system==null?"":system),user);}
    public static long completion(String trustedSystem,String user){return Math.addExact(Math.addExact(bytes(trustedSystem),bytes(user)),128L+MAX_COMPLETION_OUTPUT);}
    public static long embedding(List<String> texts){long bound=32;for(String text:texts)bound=Math.addExact(bound,Math.addExact(bytes(text),16));return bound;}
    public static long rerank(String query,List<String> documents){long bound=32;for(String document:documents)bound=Math.addExact(bound,Math.addExact(Math.addExact(bytes(query),bytes(document)),64));return bound;}
    private static long bytes(String text){return text==null?0:text.getBytes(StandardCharsets.UTF_8).length;}
}
