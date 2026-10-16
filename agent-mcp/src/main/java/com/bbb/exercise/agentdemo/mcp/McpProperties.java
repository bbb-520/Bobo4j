package com.bbb.exercise.agentdemo.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.*;

@ConfigurationProperties("app.mcp")
public record McpProperties(boolean enabled, Duration requestTimeout, Map<String,Connection> connections) {
    public McpProperties {
        requestTimeout=requestTimeout==null?Duration.ofSeconds(10):requestTimeout;
        if(requestTimeout.compareTo(Duration.ofMillis(100))<0||requestTimeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("MCP timeout out of range");
        connections=connections==null?Map.of():Map.copyOf(connections);
        if(connections.size()>8||connections.keySet().stream().anyMatch(id->!id.matches("[a-z][a-z0-9-]{0,31}")))throw new IllegalArgumentException("Invalid MCP connection IDs");
        var aliases=new HashSet<String>();
        for(var c:connections.values())for(var a:c.tools().values())if(!aliases.add(a.alias()))throw new IllegalArgumentException("Duplicate MCP alias");
    }
    public record Connection(URI url,String endpoint,String bearerToken,boolean allowInsecureHttp,Set<String> tenants,Map<String,Approval> tools) {
        public Connection {
            endpoint=endpoint==null?"/mcp":endpoint;bearerToken=bearerToken==null?"":bearerToken;
            tenants=tenants==null?Set.of():Set.copyOf(tenants);tools=tools==null?Map.of():Map.copyOf(tools);
            if(url==null||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null
                    ||!("https".equals(url.getScheme())||allowInsecureHttp&&"http".equals(url.getScheme()))
                    ||!endpoint.matches("/[A-Za-z0-9/_-]+")||endpoint.contains("..")||tools.size()>16
                    ||tools.keySet().stream().anyMatch(n->!n.matches("[A-Za-z0-9_.-]{1,128}"))
                    ||tenants.contains("*")||bearerToken.length()>4096||!bearerToken.isEmpty()&&!bearerToken.matches("[A-Za-z0-9._~+/-]+=*"))throw new IllegalArgumentException("Invalid MCP connection configuration");
        }
        @Override public String toString(){return "McpConnection[endpoint="+endpoint+", credentials=redacted]";}
    }
    public record Approval(String alias,String description,String schemaSha256,boolean readOnly,boolean noExternalCharge,McpDocumentPolicy policy) {
        public Approval(String alias,String description,String schemaSha256,boolean readOnly,boolean noExternalCharge){this(alias,description,schemaSha256,readOnly,noExternalCharge,McpDocumentPolicy.STANDARD);}
        public Approval {
            policy=policy==null?McpDocumentPolicy.STANDARD:policy;
            if(alias==null||!alias.matches("mcp[.][a-z0-9_.]{1,59}")||description==null||description.isBlank()||description.length()>1000
                    ||schemaSha256==null||!schemaSha256.matches("[a-f0-9]{64}")||!readOnly||!noExternalCharge)
                throw new IllegalArgumentException("MCP tools require reviewed schema, read-only policy and no external charge");
        }
    }
}
