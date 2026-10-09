package com.bbb.exercise.agentdemo.auth.email;
import com.bbb.exercise.agentdemo.auth.AuthService.AuthException;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class EmailRequestSource {
    private final PrincipalKeyRing keys;private final String tenant;
    public EmailRequestSource(@Value("${app.security.internal-principal-secrets:}") String secrets,
                              @Value("${app.security.internal-principal-active-key-id:current}") String active,
                              @Value("${app.security.default-tenant-id:local}") String tenant) {this.keys=new PrincipalKeyRing(secrets,active);this.tenant=tenant;}
    public String ip(ServerWebExchange exchange) {
        var header=exchange.getRequest().getHeaders().get("X-Email-Source");
        if(header!=null) {
            try {if(header.size()!=1) throw new IllegalArgumentException();var p=keys.verify(header.getFirst(),"agent-auth-service","POST /api/auth/email/code");
                if(!"agent-gateway".equals(p.service())||!tenant.equals(p.tenant())) throw new IllegalArgumentException();return p.subject();
            }catch(IllegalArgumentException e) {throw new AuthException(401,"验证码来源签名无效");}
        }
        var remote=exchange.getRequest().getRemoteAddress();return remote==null?"unknown":remote.getAddress().getHostAddress();
    }
}
