package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用级配置，集中管理可部署环境需要覆盖的参数。 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    public static class Security {
        private String anonymousCookieName = "bbb_agent_anonymous_id";
        private String sessionCookieName = "bbb_agent_session";
        private String defaultTenantId = "local";

        public String getAnonymousCookieName() {
            return anonymousCookieName;
        }

        public void setAnonymousCookieName(String anonymousCookieName) {
            this.anonymousCookieName = anonymousCookieName;
        }

        public String getSessionCookieName() { return sessionCookieName; }
        public void setSessionCookieName(String sessionCookieName) { this.sessionCookieName = sessionCookieName; }

        public String getDefaultTenantId() {
            return defaultTenantId;
        }

        public void setDefaultTenantId(String defaultTenantId) {
            this.defaultTenantId = defaultTenantId;
        }

    }
}
