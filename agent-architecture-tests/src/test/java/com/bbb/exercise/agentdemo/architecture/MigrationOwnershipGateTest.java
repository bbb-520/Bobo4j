package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 2（立即变红，无基线）：每张业务表只有一个 owner，只有一个模块在迁移里建它。
 *
 * <p>判据来自设计文档 §8 的 owner 表。作用域限定在
 * {@code agent-*&#47;src/main/resources/db/migration}：只有这个目录下的脚本有执行入口
 * （各服务 {@code spring.flyway.locations: classpath:db/migration}）。
 *
 * <p>无执行入口的 SQL 若存在，必须登记；硬切换后的目标状态允许清单为空。
 */
class MigrationOwnershipGateTest {

    private static final String NON_OWNER_MIGRATIONS = "non-owner-migrations.txt";

    /** 设计文档 §8 定义的领域 owner。 */
    private static final Map<String, String> TABLE_OWNERS = owners();

    @Test
    @DisplayName("没有模块在自己的迁移里创建别人拥有的表")
    void noModuleCreatesATableOwnedByAnotherModule() {
        List<String> violations = new ArrayList<>();
        ArchitectureState.tableCreators().forEach((table, creators) -> {
            String owner = TABLE_OWNERS.get(table);
            if (owner == null) {
                return;
            }
            for (String creator : creators) {
                if (!owner.equals(creator)) {
                    violations.add(creator + " 创建了 " + owner + " 拥有的表 " + table);
                }
            }
        });

        assertThat(violations)
                .as("跨模块建表必须回到 owner 模块；新增共享表要先在设计文档里定 owner")
                .isEmpty();
    }

    @Test
    @DisplayName("每张有 owner 的表都由 owner 模块声明")
    void everyOwnedTableIsDeclaredByItsOwnerModule() {
        Map<String, Set<String>> creators = ArchitectureState.tableCreators();
        TABLE_OWNERS.forEach((table, owner) -> assertThat(creators.getOrDefault(table, Set.of()))
                .as("%s 必须由 %s 的迁移声明", table, owner)
                .contains(owner));
    }

    @Test
    @DisplayName("有 owner 的表不能被任何模块重复建表")
    void noOwnedTableIsCreatedByMoreThanOneModule() {
        Map<String, String> duplicated = new LinkedHashMap<>();
        ArchitectureState.tableCreators().forEach((table, creators) -> {
            if (creators.size() > 1) {
                duplicated.put(table, String.join(",", creators));
            }
        });

        assertThat(duplicated)
                .as("同一张表只能有一个建表模块（每个 owner 只有一个写入者）")
                .isEmpty();
    }

    @Test
    @DisplayName("已登记的无 owner 迁移脚本必须存在")
    void registeredNonOwnerMigrationsMustStillExist() {
        List<String> registered = Baseline.keys(NON_OWNER_MIGRATIONS);
        List<String> missing = registered.stream().filter(path -> !Repository.exists(path)).toList();
        assertThat(missing)
                .as("这些脚本没有被证明「从未执行过」，不能凭一次清理动作删除；"
                        + "确需删除时先核对各环境 flyway_schema_history 与目标表是否存在，再同步更新基线")
                .isEmpty();
    }

    @Test
    @DisplayName("无执行入口的 SQL 清单与基线一致（新增也要登记）")
    void nonOwnerMigrationRegistryStaysInSync() {
        List<String> current = ArchitectureState.nonOwnerMigrations();
        assertThat(new TreeSet<>(current))
                .as("没有执行入口的 SQL 清单必须与 %s 完全一致", NON_OWNER_MIGRATIONS)
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(Baseline.keys(NON_OWNER_MIGRATIONS)));
    }

    private static Map<String, String> owners() {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String table : List.of("app_user", "auth_session", "user_api_key", "user_model_profile", "email_code", "email_send_limit", "billing_wallet", "model_usage", "payment_order", "usage_reconciliation")) {
            owners.put(table, "agent-auth-service");
        }
        for (String table : List.of("chat_conversation", "chat_message", "vision_memory")) {
            owners.put(table, "agent-chat-service");
        }
        for (String table : List.of("image_asset", "image_job")) {
            owners.put(table, "agent-media-service");
        }
        owners.put("bobo_world_item", "agent-content-service");
        owners.put("zine_generation_receipt", "agent-content-service");
        owners.put("agent_run", "agent-orchestrator-service");
        for(String table:List.of("agent_execution","agent_execution_step","agent_execution_event","agent_execution_resume","agent_execution_checkpoint","agent_execution_outbox"))owners.put(table,"agent-orchestrator-service");
        for(String table:List.of("model_call_group","model_call_attempt","model_call_attempt_history","model_fault_budget","model_call_reconciliation"))owners.put(table,"agent-auth-service");
        owners.put("chat_completion","agent-chat-service");
        for(String table:List.of("rag_document","rag_job","rag_chunk","rag_embedding_cache","rag_summary_node","rag_conversation","rag_answer","rag_trace","rag_model_call","rag_call_budget","rag_stage_snapshot"))owners.put(table,"agent-rag-service");
        return Map.copyOf(owners);
    }
}
