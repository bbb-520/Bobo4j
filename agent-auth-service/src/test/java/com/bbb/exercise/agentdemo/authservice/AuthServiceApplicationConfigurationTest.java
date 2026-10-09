package com.bbb.exercise.agentdemo.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.annotation.MapperScan;
import com.bbb.exercise.agentdemo.auth.ApiKeyCrypto;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceApplicationConfigurationTest {
    @Test
    void scansMigratedBusinessPackageForComponentsAndProperties() {
        assertThat(AuthServiceApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages())
                .contains("com.bbb.exercise.agentdemo.auth")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
        assertThat(AuthServiceApplication.class.getAnnotation(ConfigurationPropertiesScan.class).basePackages())
                .contains("com.bbb.exercise.agentdemo.runtime.config")
                .doesNotContain("com.bbb.exercise.agentdemo1_0");
    }

    @Test
    void marksSpringConstructorsExplicitlyWhenTestConstructorsExist() {
        assertThat(Arrays.stream(InternalAuthController.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count()).isEqualTo(1);
        assertThat(Arrays.stream(ApiKeyCrypto.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count()).isEqualTo(1);
    }

    @Test
    void scansModelProfileMapperPackage() {
        assertThat(AuthServiceApplication.class.getAnnotation(MapperScan.class).value())
                .contains("com.bbb.exercise.agentdemo.auth.model");
    }
}
