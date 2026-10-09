package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Final-state contracts for the migration hard cutover. */
class HardCutoverContractTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"))
            .getFileName().toString().equals("agent-architecture-tests")
            ? Path.of(System.getProperty("user.dir")).getParent()
            : Path.of(System.getProperty("user.dir"));

    @Test
    @DisplayName("legacy module and runtime references are absent")
    void legacyModuleIsAbsent() throws IOException {
        assertThat(Files.exists(ROOT.resolve("agent-chat-legacy"))).isFalse();
        assertThat(textFiles().flatMap(this::readLines)
                .filter(line -> line.contains("agent-chat-legacy") || line.contains("chat-legacy"))
                .toList())
                .as("迁移完成后源码、构建、部署和文档不能再引用 legacy")
                .isEmpty();
    }

    @Test
    @DisplayName("common module is runtime-neutral")
    void commonModuleHasNoWebFluxOrLegacyDomainRoot() throws IOException {
        String pom = Files.readString(ROOT.resolve("agent-common/pom.xml"));
        assertThat(pom).doesNotContain("spring-boot-starter-webflux", "spring-webflux");
        try (Stream<Path> paths = Files.walk(ROOT.resolve("agent-common/src/main/java"))) {
            assertThat(paths.filter(Files::isRegularFile)
                    .map(ROOT::relativize)
                    .map(Path::toString)
                    .filter(path -> path.contains("agentdemo1_0"))
                    .toList())
                    .as("agent-common 不得包含遗留业务根包")
                    .isEmpty();
        }
    }

    @Test
    void productionImplementationsAreUniqueAndHaveDomainNamespaces() {
        assertThat(ArchitectureState.duplicateMainSources()).isEmpty();
        assertThat(Repository.javaSourcesOfAllModules(false).stream()
                .filter(file -> Repository.text(file).contains("com.bbb.exercise.agentdemo1_0"))
                .map(Repository::relative).toList()).isEmpty();
    }

    @Test
    @DisplayName("zine route belongs to content")
    void zineRouteDoesNotTargetChat() throws IOException {
        String routes = readString(ROOT.resolve(
                "agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/config/GatewayRoutes.java"));
        assertThat(routes).contains("/api/zine/**");
        assertThat(routes).contains("agent-content-service");
    }

    @Test
    @DisplayName("dead internal contracts and unfinished adapters are absent")
    void deadContractsAreGone() throws IOException {
        assertThat(Files.exists(ROOT.resolve("agent-api/src/main/java/com/bbb/exercise/agentdemo/api/MediaInternalApi.java"))).isFalse();
        assertThat(Files.exists(ROOT.resolve("agent-api/src/main/java/com/bbb/exercise/agentdemo/api/AuthInternalApi.java"))).isFalse();
        assertThat(textFiles().flatMap(this::readLines)
                .filter(line -> line.contains("UnsupportedOperationException"))
                .toList())
                .as("生产源码不得以 UnsupportedOperationException 表示未完成业务")
                .isEmpty();
    }

    private Stream<Path> textFiles() throws IOException {
        return Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains("\\target\\"))
                .filter(path -> !path.toString().contains("/.git/"))
                .filter(path -> !path.toString().contains("\\.git\\"))
                .filter(path -> !path.toString().contains("\\.workbuddy\\"))
                .filter(path -> !path.toString().contains("\\.superpowers\\"))
                .filter(path -> !path.toString().contains("\\docs\\"))
                .filter(path -> !path.toString().contains("/docs/"))
                .filter(path -> !path.toString().contains("\\.idea\\"))
                .filter(path -> !path.toString().contains("/.idea/"))
                .filter(path -> !path.toString().contains("agent-architecture-tests\\src\\test\\"))
                .filter(path -> !path.toString().contains("agent-architecture-tests/src/test/"))
                .filter(path -> !path.toString().contains("\\src\\test\\"))
                .filter(path -> !path.toString().contains("/src/test/"))
                .filter(path -> path.toString().matches(".*\\.(java|xml|yml|yaml|properties|ps1|md|sql)$"));
    }

    private Stream<String> readLines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + path, e);
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + path, e);
        }
    }
}
