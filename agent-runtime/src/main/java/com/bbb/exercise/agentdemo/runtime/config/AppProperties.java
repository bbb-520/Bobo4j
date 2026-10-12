package com.bbb.exercise.agentdemo.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 共享应用配置入口，集中绑定身份和会话相关的安全参数。 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    /** 身份安全配置，声明会话与匿名 Cookie 名称以及默认租户。 */
    public static class Security {
        private String anonymousCookieName = "bbb_agent_anonymous_id";
        private String sessionCookieName = "bbb_agent_session";
        private String defaultTenantId = "local";

        public String getAnonymousCookieName() {
            return anonymousCookieName;
        }

        public void setAnonymousCookieName(String v) {
            anonymousCookieName = v;
        }

        public String getSessionCookieName() {
            return sessionCookieName;
        }

        public void setSessionCookieName(String v) {
            sessionCookieName = v;
        }

        public String getDefaultTenantId() {
            return defaultTenantId;
        }

        public void setDefaultTenantId(String v) {
            defaultTenantId = v;
        }
    }
}
