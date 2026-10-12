package com.bbb.exercise.agentdemo.auth.rbac;

import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** 权限体系启动初始化，执行受控超级管理员引导及旧计费管理员迁移。 */
@Configuration(proxyBeanMethods = false)
public class RbacStartup {
    @Bean
    ApplicationRunner migrateLegacyBillingAdmins(
            RbacService rbac, @Value("${app.billing.admin-user-ids:}") String ids) {
        return args -> rbac.migrateLegacyAdmins(ids);
    }

    @Bean
    ApplicationRunner controlledSuperAdminBootstrap(
            RbacService rbac, org.springframework.context.ConfigurableApplicationContext context) {
        return args -> {
            // Command line only. No environment/default account can silently receive this role.
            if (!args.containsOption("rbac-bootstrap-username")) {
                return;
            }

            var names = args.getOptionValues("rbac-bootstrap-username");
            var emails = args.getOptionValues("rbac-bootstrap-email");

            if (names == null || names.size() != 1 || emails == null || emails.size() != 1) {
                throw new IllegalArgumentException("初始化必须同时指定唯一用户名和邮箱");
            }

            rbac.bootstrap(names.getFirst(), emails.getFirst());

            if (args.containsOption("rbac-bootstrap-exit")) {
                org.springframework.boot.SpringApplication.exit(context, () -> 0);
            }
        };
    }
}
