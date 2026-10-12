package com.bbb.exercise.agentdemo.ragservice.parsing;

import com.bbb.exercise.agentdemo.ragservice.domain.RagData.Parsed;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.io.*;

/** 隔离进程文档解析器，为 Tika 解析设置资源边界并回收超时子进程。 */
public final class IsolatedTikaParser {
    // 1. 隔离解析：用受限子 JVM 执行 Tika，避免解析器直接占用服务进程资源。
    public Parsed parse(Path input, Path work, int maximum, Duration timeout) {
        Path output = null, argsFile = null;
        Process child = null;

        try {
            Files.createDirectories(work);
            output = Files.createTempFile(work, "parse-", ".bin");
            String javaExecutable =
                    Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    System.getProperty("os.name").startsWith("Windows")
                                            ? "java.exe"
                                            : "java")
                            .toString();

            argsFile = Files.createTempFile(work, "parser-", ".args");
            Files.writeString(
                    argsFile,
                    String.join(
                            "\n",
                            java.util.List.of(
                                    "-Xmx512m",
                                    "-XX:-UsePerfData",
                                    "-Djava.awt.headless=true",
                                    argument("-Djava.io.tmpdir=" + work.toAbsolutePath()),
                                    "-cp",
                                    argument(classpath(work)),
                                    TikaParserMain.class.getName(),
                                    argument(input.toAbsolutePath().toString()),
                                    argument(output.toString()),
                                    Integer.toString(maximum))));
            ProcessBuilder builder =
                    new ProcessBuilder(javaExecutable, "@" + argsFile.toAbsolutePath())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            String systemRoot = builder.environment().get("SystemRoot"),
                    windir = builder.environment().get("WINDIR");

            builder.environment().clear();

            if (systemRoot != null) {
                builder.environment().put("SystemRoot", systemRoot);
            }

            if (windir != null) {
                builder.environment().put("WINDIR", windir);
            }

            builder.environment().put("TEMP", work.toAbsolutePath().toString());
            builder.environment().put("TMP", work.toAbsolutePath().toString());
            child = builder.start();

            // 1.1. 超时保护：超过解析期限后终止子进程，禁止继续读取未完成产物。
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(child);
                throw error("PARSE_TIMEOUT");
            }

            // 1.2. 失败分类：把无文本层、资源超限和损坏文件转换为不同业务错误。
            switch (child.exitValue()) {
                case 0:
                    break;
                case 11:
                    throw error("NO_TEXT_LAYER");
                case 12:
                    throw error("PARSE_OUTPUT_LIMIT");
                case 13:
                    throw error("PARSE_RESOURCE_LIMIT");
                default:
                    throw error("DOCUMENT_CORRUPT_OR_ENCRYPTED");
            }

            return ParsedFile.read(output, maximum);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            RagException wrapped = error("PARSER_UNAVAILABLE");

            wrapped.initCause(e);

            throw wrapped;
        } finally {
            if (child != null && child.isAlive()) {
                kill(child);
            }

            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }

            if (argsFile != null) {
                try {
                    Files.deleteIfExists(argsFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();

        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static RagException error(String code) {
        return new RagException(code, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static String argument(String value) {
        return "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"";
    }

    // 2. 子进程类路径：兼容开发目录与打包应用，确保解析依赖在隔离进程可用。
    private static String classpath(Path work) throws Exception {
        String test = System.getProperty("surefire.test.class.path");

        if (test != null) {
            return test;
        }

        return unpackClasspath(work, System.getProperty("java.class.path"));
    }

    // 2.1. 打包依赖展开：为子 JVM 准备可直接访问的解析类路径。
    static String unpackClasspath(Path work, String cp) throws Exception {
        if (!cp.endsWith(".jar")) {
            return cp;
        }

        try (var jar = new JarFile(cp)) {
            if (jar.getEntry("BOOT-INF/classes/") == null) {
                return cp;
            }

            Path target = work.resolve("parser-runtime");

            Files.createDirectories(target);
            var entries = jar.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();

                if (!name.startsWith("BOOT-INF/classes/") && !name.startsWith("BOOT-INF/lib/")) {
                    continue;
                }

                Path path = target.resolve(name).normalize();

                if (!path.startsWith(target)) {
                    throw new IOException("jar path");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                    continue;
                }

                Files.createDirectories(path.getParent());

                if (!Files.exists(path)) {
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, path);
                    }
                }
            }

            return target.resolve("BOOT-INF/classes")
                    + File.pathSeparator
                    + target.resolve("BOOT-INF/lib")
                    + File.separator
                    + "*";
        }
    }
}
