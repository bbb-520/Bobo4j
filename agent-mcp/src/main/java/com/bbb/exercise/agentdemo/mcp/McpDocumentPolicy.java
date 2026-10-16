package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Local restrictions and bounded evidence for the four reviewed public-document tools. */
public enum McpDocumentPolicy {
    STANDARD, LEARN_SEARCH, LEARN_FETCH, REPOSITORY_STRUCTURE, REPOSITORY_QUESTION;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String REPOSITORY="^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$";
    private static final String LEARN_URL="^https://learn[.]microsoft[.]com/(?!.*[.][.])[A-Za-z0-9][A-Za-z0-9/_.-]*(?:[?](?:view|tabs)=[A-Za-z0-9_.%|-]+(?:[&](?:view|tabs)=[A-Za-z0-9_.%|-]+)*)?(?:#[A-Za-z0-9_.-]+)?$";
    public ToolSchema input(ToolSchema remote) {
        if(this==STANDARD)return remote;
        Map<String,Object> properties=switch(this) {
            case LEARN_SEARCH -> Map.of("query",question());
            case LEARN_FETCH -> Map.of("url",Map.of("type","string","maxLength",2048,"pattern",LEARN_URL));
            case REPOSITORY_STRUCTURE -> Map.of("repoName",Map.of("type","string","pattern",REPOSITORY));
            case REPOSITORY_QUESTION -> Map.of("repoName",Map.of("type","string","pattern",REPOSITORY),"question",question());
            default -> throw new IllegalStateException();
        };
        var guard=Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
        return new ToolSchema(JSON.writeValueAsString(Map.of("type","object","allOf",List.of(JSON.readValue(remote.json(),Map.class),guard))));
    }
    private static Map<String,Object> question(){return Map.of("type","string","minLength",2,"maxLength",2000,"pattern","\\S");}
    record Evidence(String text,Object structured,boolean truncated) {}
    Evidence project(String text,Object structured,Map<String,Object> arguments) {
        if(this==STANDARD)return new Evidence(text,structured,false);
        if(this==LEARN_SEARCH) {
            if(!(structured instanceof Map<?,?> object)||!(object.get("results") instanceof List<?> results))throw new IllegalArgumentException("MCP_DOCUMENT_RESULT_INVALID");
            var selected=new ArrayList<Map<String,Object>>();boolean truncated=results.size()>4;
            for(var item:results.subList(0,Math.min(4,results.size()))) {
                if(!(item instanceof Map<?,?> row)||!(row.get("title") instanceof String title)||!(row.get("contentUrl") instanceof String url)
                        ||!url.matches(LEARN_URL)||url.length()>2048)throw new IllegalArgumentException("MCP_DOCUMENT_RESULT_INVALID");
                String content=row.get("content") instanceof String value?value:"";
                truncated|=title.length()>256||content.length()>1500||Boolean.TRUE.equals(row.get("content_omitted"));
                selected.add(Map.of("title",clip(title,256),"content",clip(content,1500),"url",url));
            }
            var evidence=Map.of("results",selected,"truncated",truncated);
            return new Evidence(JSON.writeValueAsString(evidence),null,truncated);
        }
        String source=this==LEARN_FETCH?String.valueOf(arguments.get("url")):"https://deepwiki.com/"+arguments.get("repoName");
        boolean truncated=text.length()>12000;
        String suffix="\nRequested source: "+source+(truncated?"\n[Excerpt: response truncated; remaining content was not read.]":"");
        return new Evidence(clip(text,12000)+suffix,null,truncated);
    }
    private static String clip(String value,int limit){int end=Math.min(value.length(),limit);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
}
