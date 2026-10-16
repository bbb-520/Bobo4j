package com.bbb.exercise.agentdemo.orchestrator.mcp;

import com.bbb.exercise.agentdemo.orchestrator.execution.ToolSchema;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Operator opt-in presets. Pins come from reviewed source resources, never live discovery. */
@ConfigurationProperties("app.mcp.public-docs")
public record McpPublicDocumentation(boolean enabled,Set<String> tenants) {
    public McpPublicDocumentation {
        tenants=tenants==null?Set.of():Set.copyOf(tenants);
        if(enabled&&tenants.isEmpty()||tenants.size()>32||tenants.stream().anyMatch(t->!t.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")))
            throw new IllegalArgumentException("Public MCP tools require explicit tenant IDs");
    }
    public McpProperties apply(McpProperties original) {
        if(!enabled)return original;
        var connections=new LinkedHashMap<>(original.connections());
        if(connections.containsKey("mslearn")||connections.containsKey("deepwiki"))throw new IllegalArgumentException("Public MCP preset connection conflict");
        connections.put("mslearn",new McpProperties.Connection(URI.create("https://learn.microsoft.com"),"/api/mcp","",false,tenants,Map.of(
                "microsoft_docs_search",approval("learn-search","mcp.learn.search","检索 Microsoft/Azure 官方公开技术文档，输入简短公开技术问题，不发送用户文档、身份、密钥或私有资料。返回有限摘要与真实来源链接；不是全网搜索。",McpDocumentPolicy.LEARN_SEARCH),
                "microsoft_docs_fetch",approval("learn-fetch","mcp.learn.fetch","读取 learn.microsoft.com 官方文档链接，输入 url。长文仅返回明确标记的节选，引用实际来源；不能读取任意网站或私有地址。",McpDocumentPolicy.LEARN_FETCH))));
        connections.put("deepwiki",new McpProperties.Connection(URI.create("https://mcp.deepwiki.com"),"/mcp","",false,tenants,Map.of(
                "read_wiki_structure",approval("wiki-structure","mcp.deepwiki.structure","读取公开开源仓库的文档目录，repoName 为一个 owner/repo。仅适用于 DeepWiki 已收录的公开仓库，不提供 GitHub 私有访问或提交功能。",McpDocumentPolicy.REPOSITORY_STRUCTURE),
                "ask_wiki_question",approval("wiki-question","mcp.deepwiki.ask","查询公开开源仓库的技术文档，输入一个 owner/repo 和简短公开技术问题。DeepWiki 返回基于仓库资料生成的解释，需区分其与官方原文；不发送用户文件、密钥或私有代码。",McpDocumentPolicy.REPOSITORY_QUESTION))));
        return new McpProperties(original.enabled(),original.requestTimeout(),connections);
    }
    private static McpProperties.Approval approval(String resource,String alias,String description,McpDocumentPolicy policy) {
        try(var input=new ClassPathResource("mcp/public-docs/"+resource+".input.json").getInputStream()) {
            var schema=new ToolSchema(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            return new McpProperties.Approval(alias,description,schema.fingerprint(),true,true,policy);
        }catch(java.io.IOException error){throw new IllegalStateException("Reviewed MCP schema resource unavailable");}
    }
}
