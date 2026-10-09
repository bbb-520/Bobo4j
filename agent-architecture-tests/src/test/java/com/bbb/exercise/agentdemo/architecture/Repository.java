package com.bbb.exercise.agentdemo.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 仓库级路径与源码访问工具。
 *
 * <p>所有架构门禁都通过它定位文件，避免每个测试各写一套相对路径，
 * 也避免不同测试对"仓库根在哪"给出不同答案。
 */
final class Repository {
    /** 迁移期遗留包名：新服务不得再扫描它。 */
    static final String ROOT_PACKAGE = "com.bbb.exercise.agentdemo1_0";

    /** 根包在源码目录里的形态。 */
    static final String ROOT_PACKAGE_PATH = "com/bbb/exercise/agentdemo1_0/";

    /** 全部 Maven 业务模块（不含本门禁模块本身）。 */
    static final List<String> MODULES = List.of(
            "agent-common",
            "agent-api",
            "agent-runtime",
            "agent-gateway",
            "agent-auth-service",
            "agent-chat-service",
            "agent-rag-service",
            "agent-media-service",
            "agent-content-service",
            "agent-orchestrator-service");

    /** 生产模块必须遵守目标态边界。 */
    static final List<String> PRODUCTION_MODULES = MODULES;

    static final Path ROOT = locateRoot();

    private Repository() {
    }

    static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 某个模块的 Java 源码文件（递归）。目录不存在时返回空列表而不是报错。 */
    static List<Path> javaSources(String module, boolean testSources) {
        Path base = ROOT.resolve(module).resolve(testSources ? "src/test/java" : "src/main/java");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(base)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> javaSourcesOfAllModules(boolean testSources) {
        return MODULES.stream().flatMap(module -> javaSources(module, testSources).stream()).toList();
    }

    /** 仓库相对路径，统一用 {@code /} 分隔，保证 Windows / Linux 上基线内容一致。 */
    static String relative(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }

    /** 取仓库相对路径的第一段作为模块名。 */
    static String moduleOf(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(0, slash);
    }

    /** 取仓库相对路径中 {@code src/main/java/} 之后的部分。 */
    static String sourcePathWithinModule(String relativePath, boolean testSources) {
        String marker = testSources ? "/src/test/java/" : "/src/main/java/";
        int index = relativePath.indexOf(marker);
        return index < 0 ? null : relativePath.substring(index + marker.length());
    }

    static Path resolve(String relativePath) {
        return ROOT.resolve(relativePath);
    }

    static boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /** 以"整词"方式判断文本是否引用某个类型名，避免 {@code ChatService} 命中 {@code ChatServiceX}。 */
    static boolean referencesType(String text, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(text).find();
    }

    private static Path locateRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path pom = cursor.resolve("pom.xml");
            if (Files.isRegularFile(pom) && text(pom).contains("<artifactId>BoboWorld4J</artifactId>")) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException(
                "未找到聚合根 pom.xml（artifactId=BoboWorld4J）。请在 BoboWorld4J 工作区内运行本模块的测试。");
    }
}
