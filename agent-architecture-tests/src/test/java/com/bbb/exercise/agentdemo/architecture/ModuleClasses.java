package com.bbb.exercise.agentdemo.architecture;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模块加载字节码的类加载器。
 *
 * <p>为什么不直接用测试 classpath 上的 {@code Class.forName}：
 * 这些模块是 Spring Boot 应用，{@code maven package} 会把它们重新打包成 fat jar，
 * 类被挪进 {@code BOOT-INF/classes/}，普通类加载器根本找不到。
 * 结果是同一套门禁在 {@code mvn test} 下通过、在 {@code mvn package} 下报 ClassNotFoundException，
 * 属于典型的"门禁依赖构建阶段"的假失败。
 *
 * <p>这里统一从 {@code <module>/target/classes} 建 {@link URLClassLoader}，
 * 并把测试类加载器作为 parent（第三方类型如 {@code JdbcTemplate}、{@code GlobalFilter}、
 * {@code ServerWebExchange} 仍由 parent 提供，保证跨加载器时类型一致）。
 * 这样门禁结果只取决于源码与编译产物，不取决于 Maven 当前走到哪个生命周期。
 */
final class ModuleClasses {
    private static final Map<String, URLClassLoader> LOADERS = new ConcurrentHashMap<>();

    private ModuleClasses() {
    }

    /** 加载模块自己的类；模块未编译时给出可执行的修复提示。 */
    static Class<?> load(String module, String className) {
        try {
            return Class.forName(className, true, loaderFor(module));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("模块 " + module + " 中不存在类 " + className
                    + "（门禁依赖的类被删除或改名了？）", e);
        }
    }

    /** 模块已编译产物的目录（ArchUnit 的 {@code importPath} 也用它）。 */
    static Path classesOf(String module) {
        Path classes = Repository.resolve(module + "/target/classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException(module + " 还没有编译产物（" + Repository.relative(classes)
                    + "）。请带上 -am 运行，例如：mvn -pl agent-architecture-tests -am test");
        }
        return classes;
    }

    private static URLClassLoader loaderFor(String module) {
        return LOADERS.computeIfAbsent(module, key -> {
            try {
                return new URLClassLoader(new URL[]{classesOf(module).toUri().toURL()},
                        ModuleClasses.class.getClassLoader());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("无法为 " + module + " 构造类加载器", e);
            }
        });
    }
}
