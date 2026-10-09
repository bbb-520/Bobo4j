package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 闸门 3（立即变红，无基线）：AgentRun 的状态推进必须同时受 owner 与 version 约束。
 *
 * <p>三条规则，缺一条就越权或丢更新：
 * <ol>
 *     <li>只能构造注入 {@code JdbcTemplate}——不存在无参构造，也不存在内存回退；</li>
 *     <li>任何以 id 为入参的公开方法都必须同时接收 owner，杜绝"只凭 id 推进"的入口；</li>
 *     <li>状态写入 SQL 必须同时带 {@code user_id=?} 与 {@code version=?}，且自增 version。</li>
 * </ol>
 */
class OwnerScopedStateTransitionGateTest {

    private static final String SERVICE_CLASS =
            "com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService";
    private static final String RUN_CLASS =
            "com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun";

    @Test
    @DisplayName("AgentWorkflowService 只能构造注入 JdbcTemplate")
    void onlyJdbcTemplateConstructorIsAllowed() {
        Constructor<?>[] constructors = type(SERVICE_CLASS).getDeclaredConstructors();

        assertThat(constructors)
                .as("无参构造意味着会出现内存回退实现，必须不存在")
                .hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .as("唯一构造方法必须只接收 JdbcTemplate")
                .containsExactly(JdbcTemplate.class);
    }

    @Test
    @DisplayName("以 id 为入参的公开方法必须同时要求 owner")
    void everyIdTakingPublicMethodAlsoRequiresOwner() {
        List<String> offenders = Arrays.stream(type(SERVICE_CLASS).getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .filter(method -> Arrays.asList(method.getParameterTypes()).contains(UUID.class))
                .filter(method -> !Arrays.asList(method.getParameterTypes()).contains(String.class))
                .map(Method::getName)
                .toList();

        assertThat(offenders)
                .as("这些方法只凭 id 就能读写 AgentRun，缺少 owner 条件")
                .isEmpty();
    }

    @Test
    @DisplayName("状态写入 SQL 同时带 owner 与 version 条件，并且自增 version")
    void stateUpdateIsGuardedByOwnerAndVersion() {
        String source = normalizedSource(
                "agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/service/AgentWorkflowService.java");

        assertThat(source).as("内存回退实现必须已经删除").doesNotContain("ConcurrentHashMap");

        // 必须只截取 UPDATE 语句本身：整类文本里 SELECT 也带 user_id，
        // 用全文断言会让"UPDATE 丢掉 owner 条件"这种退化悄悄通过。
        String update = statementStartingWith(source, "UPDATE agent_run");
        String select = statementStartingWith(source, "FROM agent_run");

        assertThat(update).as("缺 user_id 条件就可以越权改别人的 run").contains("user_id=?");
        assertThat(update).as("缺 version 条件就会丢更新、覆盖并发结果").contains("version=?");
        assertThat(update).as("version 必须自增").contains("version=version+1");

        assertThat(select).as("读取也必须限定 owner").contains("user_id=?");
    }

    /**
     * 从规范化后的源码里截出以 {@code prefix} 开头、到 Java 字符串字面量结束的语句。
     *
     * @throws AssertionError 找不到该语句时直接失败，避免断言在空字符串上"通过"
     */
    private static String statementStartingWith(String source, String prefix) {
        int start = source.indexOf(prefix);
        assertThat(start).as("源码里必须存在以「%s」开头的 SQL 语句", prefix).isGreaterThanOrEqualTo(0);

        int end = source.indexOf('"', start);
        return end > start ? source.substring(start, end) : source.substring(start);
    }

    @Test
    @DisplayName("AgentRun 必须带 version 字段，乐观锁才有依据")
    void agentRunCarriesVersion() {
        Class<?> run = type(RUN_CLASS);
        assertThat(run.isRecord()).as("AgentRun 应为 record").isTrue();

        Optional<RecordComponent> version = Arrays.stream(run.getRecordComponents())
                .filter(component -> "version".equals(component.getName()))
                .findFirst();

        assertThat(version).as("缺少 version 组件时 user_id + version 双条件无法成立").isPresent();
        assertThat(version.orElseThrow().getType()).isEqualTo(long.class);
    }

    @Test
    @DisplayName("无 owner 调用在触达数据库之前就被拒绝")
    void ownerlessCallsAreRejectedBeforeTouchingTheDatabase() {
        // 刻意不注入 DataSource：owner 校验排在 JDBC 之前，因此能证明"没有 owner 根本走不到 SQL"。
        Object service = newService(new JdbcTemplate());
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> invoke(service, "get", new Class<?>[]{UUID.class, String.class}, id, null))
                .as("get(id, null) 必须先被拒绝")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(service, "get", new Class<?>[]{UUID.class, String.class}, id, "anonymous"))
                .as("anonymous 不是有效 owner")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(service, "advance",
                new Class<?>[]{UUID.class, statusClass(), String.class}, id, reviewing(), null))
                .as("advance(id, next, null) 必须先被拒绝")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Object newService(JdbcTemplate jdbc) {
        try {
            return type(SERVICE_CLASS).getConstructor(JdbcTemplate.class).newInstance(jdbc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AgentWorkflowService 无法用 JdbcTemplate 构造", e);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return target.getClass().getMethod(name, parameterTypes).invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 被测方法主动抛出的异常要原样透出，否则断言看到的是反射包装异常。
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("调用 " + name + " 失败", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 " + name + " 失败", e);
        }
    }

    private static Class<?> statusClass() {
        return type("com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun$RunStatus");
    }

    private static Object reviewing() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class<? extends Enum>) statusClass(), "REVIEWING");
        return value;
    }

    private static Class<?> type(String className) {
        return ModuleClasses.load("agent-orchestrator-service", className);
    }

    private static String normalizedSource(String moduleRelativePath) {
        return Repository.text(Repository.resolve(moduleRelativePath)).replaceAll("\\s+", " ");
    }
}
