package com.bbb.exercise.agentdemo.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

class McpModuleBoundaryTest {
    @Test void mcpIsAnIndependentPlainLibraryWithNoServiceOrDatabaseDependencies() {
        var pom=Repository.resolve("agent-mcp/pom.xml");
        assertThat(Files.isRegularFile(pom)).as("MCP must have its own library module").isTrue();
        String source=Repository.text(pom);
        assertThat(source).contains("<artifactId>agent-api</artifactId>")
                .doesNotContain("agent-orchestrator-service","agent-runtime","agent-auth-service","agent-chat-service",
                        "agent-rag-service","agent-media-service","agent-content-service","spring-boot-maven-plugin",
                        "starter-jdbc","mysql-connector","flyway","nacos");
    }
    @Test void mcpBytecodeCannotDependOnBusinessExecutionOrPersistence() {
        assertThat(Files.isDirectory(Repository.resolve("agent-mcp/src/main/java"))).isTrue();
        var classes=new ClassFileImporter().importPath(ModuleClasses.classesOf("agent-mcp"));
        assertThat(classes).isNotEmpty();
        noClasses().that().resideInAPackage("com.bbb.exercise.agentdemo.mcp..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bbb.exercise.agentdemo.orchestrator..","com.bbb.exercise.agentdemo.runtime..",
                        "com.bbb.exercise.agentdemo.authservice..","com.bbb.exercise.agentdemo.chatservice..",
                        "com.bbb.exercise.agentdemo.ragservice..","com.bbb.exercise.agentdemo.mediaservice..",
                        "com.bbb.exercise.agentdemo.contentservice..","org.springframework.jdbc..","jakarta.persistence..")
                .check(classes);
    }
}
