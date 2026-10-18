package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class McpPublicDocumentationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final ToolSchema OBJECT=new ToolSchema("{\"type\":\"object\"}",false);
    static final String URL="https://learn.microsoft.com/en-us/azure/developer/java/sdk/authentication/overview";
    final McpResultNormalizer normalizer=new McpResultNormalizer(List.of("sample-secret"));
    final McpCall call=new McpCall("mslearn","microsoft_docs_search","0".repeat(64));
    McpSchema.CallToolResult result(String text,Map<String,Object> structured,boolean error){
        var builder=McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).isError(error);
        if(structured!=null)builder.structuredContent(structured);return builder.build();
    }
    @Test void disabledProfileDoesNotCreateConnections(){
        var original=new McpProperties(false,null,Map.of());
        assertThat(new McpPublicDocumentation(false,Set.of()).apply(original)).isSameAs(original);
    }
    @Test void enabledPresetsPinExactlyFourReviewedToolsAndExactTenants(){
        var config=new McpPublicDocumentation(true,Set.of("tenant")).apply(new McpProperties(true,Duration.ofSeconds(30),Map.of()));
        assertThat(config.connections()).containsOnlyKeys("mslearn","deepwiki");
        assertThat(config.connections().values()).allSatisfy(c->{assertThat(c.tenants()).containsExactly("tenant");assertThat(c.bearerToken()).isEmpty();assertThat(c.allowInsecureHttp()).isFalse();});
        assertThat(config.connections().get("mslearn").tools()).containsOnlyKeys("microsoft_docs_search","microsoft_docs_fetch");
        assertThat(config.connections().get("deepwiki").tools()).containsOnlyKeys("read_wiki_structure","ask_wiki_question");
        assertThat(config.connections().values().stream().flatMap(c->c.tools().values().stream()).toList()).allSatisfy(a->{assertThat(a.schemaSha256()).matches("[a-f0-9]{64}");assertThat(a.policy()).isNotEqualTo(McpDocumentPolicy.STANDARD);});
    }
    @Test void missingWildcardTenantsAndPresetConnectionCollisionsFailClosed(){
        assertThatThrownBy(()->new McpPublicDocumentation(true,Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new McpPublicDocumentation(true,Set.of("*"))).isInstanceOf(IllegalArgumentException.class);
        var profile=new McpPublicDocumentation(true,Set.of("tenant"));
        var configured=profile.apply(new McpProperties(true,null,Map.of()));
        assertThatThrownBy(()->profile.apply(configured)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void advertisedInputAndDispatchUseBoundedPublicDocumentArguments(){
        var query=McpDocumentPolicy.LEARN_SEARCH.input(OBJECT);
        query.validate(Map.of("query","Java managed identity"));
        for(var bad:List.<Map<String,Object>>of(Map.of(),Map.of("query"," "),Map.of("query","x".repeat(2001)),Map.of("query","Java","url","http://127.0.0.1")))
            assertThatThrownBy(()->query.validate(bad)).isInstanceOf(IllegalArgumentException.class);
        var fetch=McpDocumentPolicy.LEARN_FETCH.input(OBJECT);fetch.validate(Map.of("url",URL));
        fetch.validate(Map.of("url","https://learn.microsoft.com/java/api/overview/azure/identity-readme?view=azure-java-stable#managed-identity-support"));
        for(String url:List.of("http://learn.microsoft.com/en-us/a","https://learn.microsoft.com.evil.test/en-us/a","https://user@learn.microsoft.com/en-us/a","http://127.0.0.1/","https://learn.microsoft.com/en-us/a?token=secret","https://learn.microsoft.com/en-us/../a"))
            assertThatThrownBy(()->fetch.validate(Map.of("url",url))).isInstanceOf(IllegalArgumentException.class);
        var repo=McpDocumentPolicy.REPOSITORY_QUESTION.input(OBJECT);
        repo.validate(Map.of("repoName","spring-projects/spring-ai","question","How does ToolCallback work?"));
        for(Object name:List.of("../private","https://github.com/owner/repo",List.of("owner/repo"),"owner/repo/issues"))
            assertThatThrownBy(()->repo.validate(Map.of("repoName",name,"question","why"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void longSearchProjectsReceivedSourcesAndMarksTruncationWithoutRemoteAccounting(){
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<10;i++)rows.add(Map.of("title","Identity "+i,"content","sample-secret "+"x".repeat(5000),"contentUrl",URL+"/"+i));
        var remote=result("duplicated "+"y".repeat(40000),Map.of("results",rows,"inputTokens",900000),false);
        var actual=normalizer.normalize(call,remote,OBJECT,McpDocumentPolicy.LEARN_SEARCH,Map.of("query","Java"));
        assertThat(actual).containsEntry("isError",false).containsEntry("truncated",true).containsEntry("inputTokens",0L);
        assertThat(JSON.writeValueAsString(actual)).hasSizeLessThan(16000).contains(URL+"/0","Identity 0","[redacted]").doesNotContain("sample-secret","900000");
    }
    @Test void fetchedAndGeneratedTextKeepSourceAndExplicitExcerptStatus(){
        var actual=normalizer.normalize(call,result("x".repeat(24000),null,false),null,McpDocumentPolicy.LEARN_FETCH,Map.of("url",URL));
        assertThat(actual).containsEntry("isError",false).containsEntry("truncated",true);
        assertThat(actual.get("text").toString()).contains(URL).hasSizeLessThan(14000);
        var wiki=normalizer.normalize(call,result("Generated answer",null,false),null,McpDocumentPolicy.REPOSITORY_QUESTION,Map.of("repoName","spring-projects/spring-ai","question","ToolCallback?"));
        assertThat(wiki.get("text").toString()).contains("https://deepwiki.com/spring-projects/spring-ai","Generated answer");
    }
    @Test void remoteErrorAndInvalidDeclaredOutputCannotBecomeSuccessfulEvidence(){
        var error=normalizer.normalize(call,result("error "+"x".repeat(24000),null,true),null,McpDocumentPolicy.LEARN_FETCH,Map.of("url",URL));
        assertThat(error).containsEntry("isError",true);
        var output=new ToolSchema("{\"type\":\"object\",\"required\":[\"results\"],\"properties\":{\"results\":{\"type\":\"array\"}}}",false);
        var invalid=normalizer.normalize(call,result("text",Map.of("results","wrong type"),false),output,McpDocumentPolicy.LEARN_SEARCH,Map.of("query","Java"));
        assertThat(invalid).containsEntry("isError",true).containsEntry("text","MCP_OUTPUT_SCHEMA_INVALID");
    }
    @Test void ordinaryToolsRetainTheirStrictOversizeRejection(){
        var actual=normalizer.normalize(call,result("x".repeat(24000),null,false),null);
        assertThat(actual).containsEntry("isError",true).containsEntry("text","MCP_RESULT_TOO_LARGE");
    }
    @Test void aSourceLabelDoesNotTurnAnEmptyRemoteResponseIntoEvidence(){
        var actual=normalizer.normalize(call,result("",null,false),null,McpDocumentPolicy.LEARN_FETCH,Map.of("url",URL));
        assertThat(actual).containsEntry("isError",true).containsEntry("text","MCP_EMPTY_RESULT");
    }
}
