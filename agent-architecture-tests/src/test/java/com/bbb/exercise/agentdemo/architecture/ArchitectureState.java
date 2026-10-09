package com.bbb.exercise.agentdemo.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从源码推导"当前实际架构状态"。
 *
 * <p>所有门禁测试都只依赖这里的方法，保证"基线由什么算出来"是单一实现。
 * 这里刻意只读源码，不依赖编译产物，因此任何模块单独改动后门禁都能独立复现结论。
 */
final class ArchitectureState {
    /** 组件扫描类注解；无参形态意味着"扫描所在包"。 */
    private static final Pattern SCAN_ANNOTATION = Pattern.compile(
            "@(SpringBootApplication|ComponentScan|ConfigurationPropertiesScan|MapperScan)\\s*(\\([^)]*\\))?");

    private static final Pattern ROOT_PACKAGE_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?com\\.bbb\\.exercise\\.agentdemo1_0[\\w.]*;", Pattern.MULTILINE);

    /** 带方法体的 claim 方法：命中者即"claim 实现"，而不是调用点。 */
    private static final Pattern CLAIM_METHOD = Pattern.compile(
            "\\bclaim[A-Za-z0-9_]*\\s*\\([^)]*\\)\\s*\\{");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)create\\s+table(?:\\s+if\\s+not\\s+exists)?\\s+`?([A-Za-z0-9_]+)`?");

    /**
     * Web 层入口类注解。
     *
     * <p>末尾的 {@code \b} 是刻意的：{@code @RestControllerAdvice} 必须被识别为 Web 层，
     * 但 {@code @Controller} 不能因为前缀相同而误判。{@code Controller\b} 匹配不到
     * {@code ControllerAdvice}（后面跟的是单词字符），所以两个后缀要显式列出来。
     */
    private static final Pattern WEB_LAYER_ANNOTATION = Pattern.compile(
            "@(RestController|Controller|RestControllerAdvice|ControllerAdvice)\\b");

    /**
     * 持久化能力的 import 语句。
     *
     * <p>只认 {@code import} 行，不认整类文本：注释里写一句"不要用 JdbcTemplate"
     * 不应该让门禁变红，而真正直接依赖它的人一定会有这条 import。
     */
    private static final Pattern PERSISTENCE_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(?:org\\.springframework\\.jdbc|javax\\.sql|java\\.sql"
                    + "|org\\.apache\\.ibatis|com\\.baomidou\\.mybatisplus)\\.",
            Pattern.MULTILINE);

    private static final Pattern MAPPER_ANNOTATION = Pattern.compile("@Mapper\\b");

    /** agent-api 契约的源码根目录与包名。 */
    private static final String API_BASE = "agent-api/src/main/java/com/bbb/exercise/agentdemo/api";
    private static final String API_PACKAGE = "com.bbb.exercise.agentdemo.api";

    /** agent-common 里唯一被允许长期存在的包（设计文档 §6.1 的"无业务归属公共能力"）。 */
    private static final String COMMON_PACKAGE_PATH = "com/bbb/exercise/agentdemo/common/";

    /** agent-common 归属越界的类别。前三个是"立即变红"，最后一个是存量快照。 */
    static final String CATEGORY_LEGACY_ROOT = "LEGACY_ROOT_PACKAGE";
    static final String CATEGORY_WEB_LAYER = "WEB_LAYER";
    static final String CATEGORY_PERSISTENCE = "PERSISTENCE";
    static final String CATEGORY_OUTSIDE_COMMON = "OUTSIDE_COMMON_PACKAGE";

    /** 今天已经为 0、不允许再出现的归属越界类别。 */
    static final List<String> HARD_COMMON_CATEGORIES = List.of(
            CATEGORY_WEB_LAYER, CATEGORY_PERSISTENCE, CATEGORY_OUTSIDE_COMMON);

    private ArchitectureState() {
    }

    /**
     * 跨模块重复的主源码全限定名：同一个类被复制到多个模块。
     *
     * <p>这是 Phase C 的清理目标，当前存量较大，因此只做"不许新增"的快照门禁。
     */
    static List<String> duplicateMainSources() {
        return new ArrayList<>(duplicateMainSourceOwners().keySet());
    }

    /**
     * 重复类 → 持有它的模块集合。
     *
     * <p><b>只包含真正重复的条目</b>（持有模块数 &gt; 1）。单副本文件不是重复类，
     * 混进来会让门禁把"新写了一个普通类"误判成"新增重复"。
     * 返回 {@code TreeMap}/{@code TreeSet}，保证跨平台输出顺序一致。
     */
    static Map<String, Set<String>> duplicateMainSourceOwners() {
        Map<String, Set<String>> all = new TreeMap<>();
        for (Path file : Repository.javaSourcesOfAllModules(false)) {
            String relative = Repository.relative(file);
            String withinModule = Repository.sourcePathWithinModule(relative, false);
            if (withinModule != null && withinModule.contains(Repository.ROOT_PACKAGE_PATH)) {
                all.computeIfAbsent(withinModule, key -> new TreeSet<>()).add(Repository.moduleOf(relative));
            }
        }

        Map<String, Set<String>> duplicates = new TreeMap<>();
        all.forEach((fqn, owners) -> {
            if (owners.size() > 1) {
                duplicates.put(fqn, owners);
            }
        });
        return duplicates;
    }

    /**
     * 扫描根包 {@code com.bbb.exercise.agentdemo1_0} 的组件扫描声明。
     *
     * <p>行格式：{@code 模块|模块内相对路径|注解(限定/无参)}。
     * 用"注解所在文件 + 注解实参"而不是行号做标识，避免上游编辑导致基线整体漂移。
     */
    static List<String> rootPackageScanDeclarations() {
        List<String> declarations = new ArrayList<>();
        for (Path file : Repository.javaSourcesOfAllModules(false)) {
            String relative = Repository.relative(file);
            String module = Repository.moduleOf(relative);
            String collapsed = Repository.text(file).replaceAll("\\s+", " ");
            Matcher matcher = SCAN_ANNOTATION.matcher(collapsed);
            while (matcher.find()) {
                String annotation = matcher.group(1);
                String arguments = matcher.group(2);
                boolean qualified = arguments != null && arguments.contains(Repository.ROOT_PACKAGE);
                boolean unqualifiedInsideRootPackage =
                        arguments == null && relative.contains(Repository.ROOT_PACKAGE_PATH);
                if (qualified || unqualifiedInsideRootPackage) {
                    declarations.add(module + "|" + relative.substring(module.length() + 1) + "|@" + annotation
                            + (qualified ? "(root-package)" : "(unqualified)"));
                }
            }
        }
        return declarations.stream().distinct().sorted().toList();
    }

    /**
     * 新风格包（{@code com.bbb.exercise.agentdemo.*}）编译期 import 根包的地方。
     *
     * <p>行格式：{@code 模块|模块内相对路径}。这类依赖是"根包扫描"关不掉的真实原因，
     * 也是 Phase B/C 的输入清单。
     */
    static List<String> rootPackageImports() {
        List<String> imports = new ArrayList<>();
        for (Path file : Repository.javaSourcesOfAllModules(false)) {
            String relative = Repository.relative(file);
            String module = Repository.moduleOf(relative);
            String withinModule = relative.substring(module.length() + 1);
            // 根包内部的互相 import 不算耦合，只有新风格包引用根包才算。
            if (withinModule.contains(Repository.ROOT_PACKAGE_PATH)) {
                continue;
            }
            if (ROOT_PACKAGE_IMPORT.matcher(Repository.text(file)).find()) {
                imports.add(module + "|" + withinModule);
            }
        }
        return imports.stream().distinct().sorted().toList();
    }

    /**
     * {@code agent-api} 契约的外部引用文件数。
     *
     * <p>键是全限定名，值是"agent-api 之外的模块里引用该类型的文件数"。
     * 统计源码与测试，避免把只被自己测试引用的契约误判为活契约。
     */
    static Map<String, Integer> apiContractExternalReferences() {
        Map<String, Integer> references = new TreeMap<>();
        Path apiBase = Repository.resolve(API_BASE);
        for (Path contract : apiContractFiles()) {
            String withinApi = apiBase.relativize(contract).toString().replace('\\', '/');
            String fqn = API_PACKAGE + "."
                    + withinApi.substring(0, withinApi.length() - ".java".length()).replace('/', '.');
            String simpleName = contract.getFileName().toString().replace(".java", "");

            int external = 0;
            for (Path candidate : Repository.javaSourcesOfAllModules(false)) {
                if (!"agent-api".equals(Repository.moduleOf(Repository.relative(candidate)))
                        && Repository.referencesType(Repository.text(candidate), simpleName)) {
                    external++;
                }
            }
            for (Path candidate : Repository.javaSourcesOfAllModules(true)) {
                if (!"agent-api".equals(Repository.moduleOf(Repository.relative(candidate)))
                        && Repository.referencesType(Repository.text(candidate), simpleName)) {
                    external++;
                }
            }
            references.put(fqn, external);
        }
        return references;
    }

    /** 模块 Flyway 目录之外、且没有执行入口的 SQL 脚本（已登记，禁止静默删除）。 */
    static List<String> nonOwnerMigrations() {
        List<String> scripts = new ArrayList<>();
        collectSql("infra", scripts);
        return scripts.stream().distinct().sorted().toList();
    }

    /** 定义了 claim 方法（带方法体）的主源码文件，行格式：{@code 模块|模块内相对路径}。 */
    static List<String> claimImplementationFiles() {
        List<String> files = new ArrayList<>();
        for (Path file : Repository.javaSourcesOfAllModules(false)) {
            if (CLAIM_METHOD.matcher(Repository.text(file)).find()) {
                String relative = Repository.relative(file);
                String module = Repository.moduleOf(relative);
                files.add(module + "|" + relative.substring(module.length() + 1));
            }
        }
        return files.stream().distinct().sorted().toList();
    }

    /**
     * 直接依赖持久化能力的 Web 层类。
     *
     * <p>设计文档 §15.2 要求「Controller 不直接依赖 JdbcTemplate」。行格式：
     * {@code 模块|模块内相对路径|命中原因}。命中原因会直接写进失败信息，
     * 避免只看到"有一条违规"却不知道是 import 还是注解。
     */
    static List<String> controllersDependingOnPersistence() {
        List<String> violations = new ArrayList<>();
        for (Path file : Repository.javaSourcesOfAllModules(false)) {
            String text = Repository.text(file);
            if (!WEB_LAYER_ANNOTATION.matcher(text).find()) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (PERSISTENCE_IMPORT.matcher(text).find()) {
                reasons.add("import 持久化包");
            }
            if (MAPPER_ANNOTATION.matcher(text).find()) {
                reasons.add("@Mapper");
            }
            if (!reasons.isEmpty()) {
                String relative = Repository.relative(file);
                String module = Repository.moduleOf(relative);
                violations.add(module + "|" + relative.substring(module.length() + 1)
                        + "|" + String.join(" + ", reasons));
            }
        }
        return violations.stream().distinct().sorted().toList();
    }

    /**
     * {@code agent-common} 的归属越界清单，行格式：
     * {@code 模块内相对路径(含 src/main/java 前缀)|类别}。
     *
     * <p>路径与同目录的 {@code root-package-imports.txt} 保持同一口径，
     * 便于两份清单直接对照。
     *
     * <p>判据分两档（见 {@link ArchitectureState#HARD_COMMON_CATEGORIES}）：
     * <ul>
     *     <li>{@code LEGACY_ROOT_PACKAGE}：文件还在遗留根包下，是 Phase B 关扫描的硬依赖，
     *     存量 18 条，走快照；</li>
     *     <li>{@code WEB_LAYER} / {@code PERSISTENCE} / {@code OUTSIDE_COMMON_PACKAGE}：今天为 0，
     *     立即变红——防止有人把业务类塞进"合法公共包"来绕过收敛。</li>
     * </ul>
     */
    static List<String> commonModuleBoundaryViolations() {
        List<String> violations = new ArrayList<>();
        for (Path file : Repository.javaSources("agent-common", false)) {
            String relative = Repository.relative(file);
            String module = Repository.moduleOf(relative);
            String withinModule = relative.substring(module.length() + 1);
            String sourcePath = withinModule.substring("src/main/java/".length());

            if (sourcePath.startsWith(Repository.ROOT_PACKAGE_PATH)) {
                violations.add(withinModule + "|" + CATEGORY_LEGACY_ROOT);
                continue;
            }
            if (!sourcePath.startsWith(COMMON_PACKAGE_PATH)) {
                violations.add(withinModule + "|" + CATEGORY_OUTSIDE_COMMON);
                continue;
            }
            String text = Repository.text(file);
            if (WEB_LAYER_ANNOTATION.matcher(text).find()) {
                violations.add(withinModule + "|" + CATEGORY_WEB_LAYER);
            }
            if (PERSISTENCE_IMPORT.matcher(text).find() || MAPPER_ANNOTATION.matcher(text).find()) {
                violations.add(withinModule + "|" + CATEGORY_PERSISTENCE);
            }
        }
        return violations.stream().distinct().sorted().toList();
    }

    /** 模块自己声明的 Flyway 迁移里创建的表 → 建表模块集合。 */
    static Map<String, Set<String>> tableCreators() {
        Map<String, Set<String>> creators = new TreeMap<>();
        for (String module : Repository.MODULES) {
            Path dir = Repository.resolve(module + "/src/main/resources/db/migration");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path migration : stream.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                    Matcher matcher = CREATE_TABLE.matcher(Repository.text(migration));
                    while (matcher.find()) {
                        creators.computeIfAbsent(matcher.group(1).toLowerCase(java.util.Locale.ROOT),
                                key -> new TreeSet<>()).add(module);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return creators;
    }

    /**
     * 解析 YAML 里的顶层 {@code server.port}，支持 {@code 18085} 与 {@code ${SERVER_PORT:18085}}。
     *
     * <p>只认顶层 {@code server:} 块，避免误读 nacos / redis / management 下的端口。
     */
    static OptionalInt declaredServerPort(Path yaml) {
        List<String> lines = readLines(yaml);
        for (int i = 0; i < lines.size(); i++) {
            if (!"server:".equals(lines.get(i).trim())) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.isBlank()) {
                    continue;
                }
                if (indentOf(line) == 0) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("port:")) {
                    return OptionalInt.of(parsePort(trimmed.substring("port:".length()).trim()));
                }
            }
        }
        return OptionalInt.empty();
    }

    /** 解析 {@code spring.application.name}，同时支持嵌套写法与 {@code application.name} 扁平写法。 */
    static String declaredApplicationName(Path yaml) {
        List<String> lines = readLines(yaml);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("application.name:")) {
                return trimmed.substring("application.name:".length()).trim();
            }
            if (!"application:".equals(trimmed)) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String candidate = lines.get(j);
                if (candidate.isBlank()) {
                    continue;
                }
                if (indentOf(candidate) <= indentOf(lines.get(i))) {
                    break;
                }
                if (candidate.trim().startsWith("name:")) {
                    return candidate.trim().substring("name:".length()).trim();
                }
            }
        }
        return null;
    }

    static Map<String, Integer> readmeModulePorts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\|\\s*`(agent-[a-z-]+)`\\s*\\|\\s*(\\d+)\\s*\\|");
        for (String line : readLines(Repository.resolve("README.md"))) {
            Matcher matcher = row.matcher(line);
            if (matcher.find()) {
                ports.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return ports;
    }

    /** README 顶层架构图里 {@code agent-xxx:端口} 的写法。 */
    static Map<String, Integer> readmeTopologyPorts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        Pattern topology = Pattern.compile("(agent-[a-z-]+):(\\d{4,5})");
        for (String line : readLines(Repository.resolve("README.md"))) {
            Matcher matcher = topology.matcher(line);
            while (matcher.find()) {
                ports.putIfAbsent(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return ports;
    }

    private static List<Path> apiContractFiles() {
        Path base = Repository.resolve(API_BASE);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(base)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void collectSql(String moduleRelativeBase, List<String> sink) {
        Path base = Repository.resolve(moduleRelativeBase);
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(Repository::relative)
                    .forEach(sink::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + Repository.relative(file), e);
        }
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static int parsePort(String raw) {
        Matcher placeholder = Pattern.compile("\\$\\{[^:}]+:(\\d+)}").matcher(raw);
        if (placeholder.matches()) {
            return Integer.parseInt(placeholder.group(1));
        }
        return Integer.parseInt(raw.replace("\"", "").replace("'", ""));
    }
}
