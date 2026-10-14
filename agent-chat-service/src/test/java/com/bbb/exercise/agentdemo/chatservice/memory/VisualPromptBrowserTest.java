package com.bbb.exercise.agentdemo.chatservice.memory;

import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.runtime.config.AppProperties;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import com.bbb.exercise.agentdemo.runtime.security.BrowserSecurityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real browser security + resolver + H2: a missing route or wrong permission cannot pass. */
class VisualPromptBrowserTest {
    private static final String PATH = "/api/settings/visual-memory/prompts";
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-10-14T12:30:00.123");
    private AnnotationConfigApplicationContext context;
    private WebTestClient client;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class, BrowserSecurityConfiguration.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).build();
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Configuration
    @EnableWebFlux
    static class TestConfig {
        @Bean JdbcTemplate jdbc() {
            var db = new ObservedJdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
            db.execute("CREATE TABLE vision_memory(id BIGINT PRIMARY KEY,tenant_id VARCHAR(128),"
                    + "user_id VARCHAR(255),kind VARCHAR(32),content CLOB,structured_json CLOB,"
                    + "source VARCHAR(64),active INT,updated_at TIMESTAMP(3))");
            return db;
        }

        @Bean BrowserIdentityProvider browserIdentities() {
            return token -> switch (token) {
                case "alice" -> Mono.just(new BrowserIdentityProvider.BrowserUser("alice", "tenant", Set.of("USER"), Set.of("chat:use")));
                case "bob" -> Mono.just(new BrowserIdentityProvider.BrowserUser("bob", "tenant", Set.of("USER"), Set.of("chat:use")));
                case "other-tenant" -> Mono.just(new BrowserIdentityProvider.BrowserUser("alice", "other", Set.of("USER"), Set.of("chat:use")));
                case "denied" -> Mono.just(new BrowserIdentityProvider.BrowserUser("alice", "tenant", Set.of("USER"), Set.of("profile:manage")));
                default -> Mono.empty();
            };
        }

        @Bean InternalServiceClient transport() { return mock(InternalServiceClient.class); }
        @Bean ChatIdentityResolver identities(InternalServiceClient transport) {
            return new ChatIdentityResolver(new AppProperties(), transport);
        }
        @Bean VisionEmbeddings embeddings() { return mock(VisionEmbeddings.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean ModelCallClient billing() { return mock(ModelCallClient.class); }
        @Bean VisionMemoryService memories(JdbcTemplate jdbc, VisionEmbeddings embeddings) {
            return spy(new VisionMemoryService(jdbc, embeddings, .55));
        }
        @Bean VisualPromptService prompts(JdbcTemplate jdbc) { return new VisualPromptService(jdbc); }
        @Bean VisualPromptController promptController(ChatIdentityResolver identities, VisualPromptService prompts) {
            return new VisualPromptController(identities, prompts);
        }
    }

    @Test
    void browserSecurityRequiresRealSessionAndChatPermission() {
        client.get().uri(PATH).header("X-Permissions", "chat:use").header("X-User-Id", "alice")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(PATH).cookie("bbb_agent_session", "invalid")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(PATH).cookie("bbb_agent_session", "denied").header("X-Permissions", "chat:use")
                .exchange().expectStatus().isForbidden();
        get("alice", "").expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectBody().jsonPath("$.items.length()").isEqualTo(0).jsonPath("$.nextCursor").isEmpty();
    }

    @Test
    void exposesOnlyActiveExactTenantAndUserAndCopiesActualContent() {
        String content = "雨后街头\n  保留 JSON {\"ratio\":\"3:4\"}\n用户指令：原文不改";
        insert(1, "tenant", "alice", 1, TIME, content,
                "{\"summary\":\"雨后街头\",\"subjects\":[\"行人\",\"霓虹\"],\"palette\":\"青橙\",\"composition\":\"低视角\",\"mood\":\"安静\",\"sourceAssetId\":\"asset-7\"}");
        insert(2, "tenant", "bob", 1, TIME, "别人的内容", null);
        insert(3, "other", "alice", 1, TIME, "别的租户", null);
        insert(4, "tenant", "alice", 0, TIME, "已停用", null);
        get("alice", "").expectStatus().isOk().expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo("memory:1")
                .jsonPath("$.items[0].title").isEqualTo("雨后街头")
                .jsonPath("$.items[0].content").isEqualTo(content)
                .jsonPath("$.items[0].category").isEqualTo("visual-memory")
                .jsonPath("$.items[0].source").isEqualTo("visual-memory")
                .jsonPath("$.items[0].updatedAt").isEqualTo("2026-10-14T12:30:00.123")
                .jsonPath("$.items[0].subjects[0]").isEqualTo("行人")
                .jsonPath("$.items[0].palette").isEqualTo("青橙")
                .jsonPath("$.items[0].composition").isEqualTo("低视角")
                .jsonPath("$.items[0].mood").isEqualTo("安静")
                .jsonPath("$.items[0].sourceAssetId").isEqualTo("asset-7");
        assertThat(items(page("bob", ""))).extracting(item -> item.get("content")).containsExactly("别人的内容");
        assertThat(items(page("other-tenant", ""))).extracting(item -> item.get("content")).containsExactly("别的租户");
    }

    @Test
    void defaultPageIs24AndTiesStayStableAcrossInsertAndDeletion() {
        for (int i = 1; i <= 49; i++) insert(i, "tenant", "alice", 1, TIME, "记忆 " + i, null);
        Map<String, Object> first = page("alice", "");
        assertThat(ids(first)).hasSize(24).startsWith("memory:49", "memory:48").endsWith("memory:26");
        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).isNotBlank();
        insert(50, "tenant", "alice", 1, TIME.plusSeconds(1), "新增", null);
        jdbc.update("DELETE FROM vision_memory WHERE id=20");
        Map<String, Object> second = page("alice", "?cursor=" + cursor);
        assertThat(ids(second)).hasSize(24).startsWith("memory:25").endsWith("memory:1").doesNotContain("memory:20", "memory:50");
        assertThat(second.get("nextCursor")).isNull();
        var all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).hasSize(48).doesNotHaveDuplicates();
    }

    @Test
    void nonTiedTimestampsAndLastPageUseTheSameStableKeyset() {
        insert(1, "tenant", "alice", 1, TIME.minusSeconds(1), "旧", null);
        insert(2, "tenant", "alice", 1, TIME, "新", null);
        insert(3, "tenant", "alice", 1, TIME, "同一时刻", null);
        var first = page("alice", "?limit=1");
        assertThat(ids(first)).containsExactly("memory:3");
        var second = page("alice", "?limit=1&cursor=" + first.get("nextCursor"));
        assertThat(ids(second)).containsExactly("memory:2");
        var third = page("alice", "?limit=1&cursor=" + second.get("nextCursor"));
        assertThat(ids(third)).containsExactly("memory:1");
        assertThat(third.get("nextCursor")).isNull();
    }

    @Test
    void searchesStoredSceneContentAndTreatsWildcardsLiterally() {
        insert(1, "tenant", "alice", 1, TIME, "正文猫 portrait 陶瓷 靛蓝 留白 静谧 用户指令：记住", "{\"subjects\":[\"陶瓷\"],\"palette\":\"靛蓝\",\"composition\":\"留白\",\"mood\":\"静谧\"}");
        insert(2, "tenant", "alice", 1, TIME, "另一张图 100%_!", null);
        insert(3, "tenant", "alice", 1, TIME, "通用内容 100XX", null);
        for (String q : List.of("猫", "PORTRAIT", "陶瓷", "靛蓝", "留白", "静谧"))
            assertThat(ids(search("alice", q))).as(q).containsExactly("memory:1");
        for (String q : List.of("%", "_", "!", "100%_!"))
            assertThat(ids(search("alice", q))).as(q).containsExactly("memory:2");
        assertThat(items(search("alice", "' OR 1=1 --"))).isEmpty();
    }

    @Test
    void corruptOrWronglyTypedMetadataFallsBackToRealContentWithoutInventingFields() {
        insert(1, "tenant", "alice", 1, TIME, "实际记忆\n原始创作要求", "{invalid");
        insert(2, "tenant", "alice", 1, TIME, "真实正文二", "[\"summary\",\"伪造\"]");
        insert(3, "tenant", "alice", 1, TIME, "真实正文三", "{\"summary\":{},\"subjects\":[\"猫\",3,{\"secret\":\"credential\"}],\"palette\":7,\"composition\":[],\"mood\":false,\"sourceAssetId\":{}}");
        var result = items(page("alice", ""));
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).containsEntry("title", "真实正文三").containsEntry("content", "真实正文三")
                .containsEntry("subjects", List.of("猫")).containsEntry("palette", "").containsEntry("sourceAssetId", "");
        assertThat(result.get(1)).containsEntry("title", "真实正文二").containsEntry("subjects", List.of());
        assertThat(result.get(2)).containsEntry("title", "实际记忆").containsEntry("content", "实际记忆\n原始创作要求")
                .containsEntry("subjects", List.of()).containsEntry("palette", "").containsEntry("composition", "").containsEntry("mood", "");
        assertThat(result.toString()).doesNotContain("credential", "伪造", "quality", "score", "优质");
    }

    @Test
    void searchDoesNotExposeUnknownMetadataFieldsOrTechnicalFieldNames() {
        insert(1, "tenant", "alice", 1, TIME, "正常记忆 风景 山", "{\"summary\":\"风景\",\"subjects\":[\"山\"],\"privateKey\":\"hidden-credential\",\"sourceAssetId\":\"asset-private\"}");
        assertThat(items(search("alice", "hidden-credential"))).isEmpty();
        assertThat(items(search("alice", "summary"))).isEmpty();
        assertThat(ids(search("alice", "风景"))).containsExactly("memory:1");
    }

    @Test
    void contentSearchIgnoresLegacyMetadataAndReadsOnlyLimitPlusOneRows() {
        insert(1, "tenant", "alice", 1, TIME, "cat one", null);
        insert(2, "tenant", "alice", 1, TIME, "cat two", null);
        for (int i = 3; i <= 50; i++) insert(i, "tenant", "alice", 1, TIME, "legacy note", "{\"summary\":\"cat\"}");
        var result = page("alice", "?q=cat&limit=1");
        assertThat(ids(result)).containsExactly("memory:2");
        assertThat(result.get("nextCursor")).isNotNull();
        var observed = (ObservedJdbcTemplate) jdbc;
        assertThat(observed.reads).isEqualTo(1);
        assertThat(observed.lastLimit).isEqualTo(2);
    }

    @Test
    void technicalMetadataMatchesCannotTriggerRepeatedDatabaseReads() {
        for (int i = 1; i <= 60; i++) insert(i, "tenant", "alice", 1, TIME, "legacy note", "{\"privateKey\":\"cat\"}");
        assertThat(items(search("alice", "cat"))).isEmpty();
        var observed = (ObservedJdbcTemplate) jdbc;
        assertThat(observed.reads).isEqualTo(1);
        assertThat(observed.lastLimit).isEqualTo(25);
    }

    @Test
    void cursorIsBoundToExactOwnerTenantAndNormalizedQuery() {
        for (int i = 1; i <= 3; i++) insert(i, "tenant", "alice", 1, TIME, "cat " + i, null);
        String cursor = (String) page("alice", "?q=cat&limit=1").get("nextCursor");
        get("bob", "?q=cat&cursor=" + cursor).expectStatus().isBadRequest();
        get("other-tenant", "?q=cat&cursor=" + cursor).expectStatus().isBadRequest();
        get("alice", "?q=dog&cursor=" + cursor).expectStatus().isBadRequest();
        assertThat(ids(page("alice", "?q=%20cat%20&limit=1&cursor=" + cursor))).containsExactly("memory:2");
    }

    @Test
    void rejectsMalformedOversizedAndInjectedCursors() {
        for (String value : List.of("invalid", "a".repeat(5000), encoded("{}"), encoded("[1,2]"),
                encoded("{\"version\":1,\"tenantId\":\"tenant\",\"userId\":\"alice\",\"q\":\"\",\"updatedAt\":\"not-a-date\",\"id\":1}"),
                encoded("{\"version\":4294967297,\"tenantId\":\"tenant\",\"userId\":\"alice\",\"q\":\"\",\"updatedAt\":\"2026-10-14T12:30:00.123\",\"id\":1}"),
                encoded("{\"version\":1,\"tenantId\":\"tenant\",\"userId\":\"alice\",\"q\":\"\",\"updatedAt\":\"2026-10-14T12:30:00.123\",\"id\":\"1 OR 1=1\"}")))
            get("alice", "?cursor=" + value).expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vision_memory", Integer.class)).isZero();
    }

    @Test
    void rejectsInvalidSearchAndPageBounds() {
        for (String query : List.of("?limit=0", "?limit=-1", "?limit=25", "?limit=not-a-number", "?limit=2147483648", "?q=" + "a".repeat(161)))
            get("alice", query).expectStatus().isBadRequest();
        get("alice", "?q=" + "a".repeat(160) + "&limit=24").expectStatus().isOk();
        get("alice", "?q=&cursor=").expectStatus().isOk();
    }

    @Test
    void browsingSearchAndPaginationNeverCallModelsReindexOrBilling() {
        insert(1, "tenant", "alice", 1, TIME, "cat 1", null);
        insert(2, "tenant", "alice", 1, TIME, "cat 2", null);
        var first = page("alice", "?q=cat&limit=1");
        page("alice", "?q=cat&cursor=" + first.get("nextCursor"));
        verifyNoInteractions(context.getBean(VisionEmbeddings.class), context.getBean(EmbeddingModel.class),
                context.getBean(ChatModel.class), context.getBean(ModelCallClient.class),
                context.getBean(VisionMemoryService.class), context.getBean(InternalServiceClient.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vision_memory", Integer.class)).isEqualTo(2);
    }

    private WebTestClient.ResponseSpec get(String token, String query) {
        return client.get().uri(java.net.URI.create(PATH + query)).cookie("bbb_agent_session", token).exchange();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> page(String token, String query) {
        return get(token, query).expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*private.*no-store.*")
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> search(String token, String q) {
        return client.get().uri(builder -> builder.path(PATH).queryParam("q", q).build())
                .cookie("bbb_agent_session", token).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<String> ids(Map<String, Object> page) {
        return items(page).stream().map(item -> (String) item.get("id")).toList();
    }

    private void insert(long id, String tenant, String user, int active, LocalDateTime updated, String content, String metadata) {
        jdbc.update("INSERT INTO vision_memory VALUES(?,?,?,'VISUAL_PREFERENCE',?,?,'SCENE_CARD',?,?)", id, tenant, user, content, metadata, active, updated);
    }

    private static String encoded(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static class ObservedJdbcTemplate extends JdbcTemplate {
        int reads;
        int lastLimit;

        ObservedJdbcTemplate(DriverManagerDataSource source) { super(source); }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            reads++;
            lastLimit = ((Number) args[args.length - 1]).intValue();
            return super.query(sql, mapper, args);
        }
    }
}
