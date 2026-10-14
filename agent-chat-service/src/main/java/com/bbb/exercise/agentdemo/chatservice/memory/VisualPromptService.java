package com.bbb.exercise.agentdemo.chatservice.memory;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Browsing never enters the embedding, retrieval, generation or accounting paths. */
@Service
public class VisualPromptService {
    private static final int MAX_LIMIT = 24;
    private static final int MAX_QUERY = 160;
    private static final int MAX_CURSOR = 4096;
    private static final Set<String> CURSOR_FIELDS = Set.of("version", "tenantId", "userId", "q", "updatedAt", "id");
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public VisualPromptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page browse(ChatIdentity owner, String query, int limit, String cursor) {
        requireOwner(owner);
        if (limit < 1 || limit > MAX_LIMIT) throw problem(400, "每页视觉记忆数量须为 1～24");
        String q = normalizeQuery(query);
        Position before = decode(cursor, owner, q);
        var rows = candidates(owner, q, before, limit + 1);
        boolean more = rows.size() > limit;
        var shown = rows.subList(0, Math.min(limit, rows.size()));
        var items = shown.stream().map(this::prompt).toList();
        String next = null;
        if (more) {
            Stored last = shown.getLast();
            next = encode(owner, q, new Position(last.updatedAt(), last.id()));
        }
        return new Page(items, next);
    }

    private List<Stored> candidates(ChatIdentity owner, String q, Position before, int batchSize) {
        String sql = "SELECT id,content,structured_json,updated_at FROM vision_memory WHERE tenant_id=? AND user_id=? AND active=1";
        var args = new ArrayList<Object>();
        args.add(owner.tenantId()); args.add(owner.userId());
        if (!q.isEmpty()) {
            // rememberScene already stores the summary, subjects, palette, composition,
            // mood and user instructions in content. Legacy metadata is display-only.
            sql += " AND LOWER(content) LIKE ? ESCAPE '!'";
            args.add(like(q));
        }
        if (before != null) {
            sql += " AND (updated_at<? OR (updated_at=? AND id<?))";
            args.add(Timestamp.valueOf(before.updatedAt()));
            args.add(Timestamp.valueOf(before.updatedAt()));
            args.add(before.id());
        }
        sql += " ORDER BY updated_at DESC,id DESC LIMIT ?";
        args.add(batchSize);
        return jdbc.query(sql, (rs, n) -> new Stored(rs.getLong("id"), rs.getString("content"),
                rs.getString("structured_json"), rs.getTimestamp("updated_at").toLocalDateTime()), args.toArray());
    }

    private Prompt prompt(Stored row) {
        JsonNode metadata = null;
        try {
            if (row.metadata() != null) {
                JsonNode value = json.readTree(row.metadata());
                if (value != null && value.isObject()) metadata = value;
            }
        } catch (RuntimeException corrupt) {
            // Legacy or corrupted metadata cannot replace the actual memory content.
        }
        var subjects = new ArrayList<String>();
        JsonNode subjectValues = metadata == null ? null : metadata.get("subjects");
        if (subjectValues != null && subjectValues.isArray()) {
            for (JsonNode value : subjectValues) if (value.isString() && !value.asString().isBlank()) subjects.add(value.asString().trim());
        }
        String summary = text(metadata, "summary");
        String content = row.content() == null ? "" : row.content();
        String title = summary.isBlank()
                ? content.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("")
                : summary;
        title = title.codePoints().limit(100).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return new Prompt("memory:" + row.id(), title, content, "visual-memory", "visual-memory", row.updatedAt().toString(),
                List.copyOf(subjects), text(metadata, "palette"), text(metadata, "composition"), text(metadata, "mood"), text(metadata, "sourceAssetId"));
    }

    private static String text(JsonNode metadata, String key) {
        JsonNode value = metadata == null ? null : metadata.get(key);
        return value != null && value.isString() ? value.asString().trim() : "";
    }

    private static String like(String q) {
        return "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String normalizeQuery(String query) {
        if (query == null) return "";
        if (query.length() > MAX_QUERY || query.codePoints().anyMatch(Character::isISOControl)) throw problem(400, "搜索词须不超过 160 字符");
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private String encode(ChatIdentity owner, String q, Position position) {
        String payload = json.writeValueAsString(Map.of("version", 1, "tenantId", owner.tenantId(), "userId", owner.userId(),
                "q", q, "updatedAt", position.updatedAt().toString(), "id", position.id()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private Position decode(String cursor, ChatIdentity owner, String q) {
        if (cursor == null || cursor.isEmpty()) return null;
        try {
            if (cursor.length() > MAX_CURSOR || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) throw new IllegalArgumentException();
            JsonNode value = json.readTree(bytes);
            if (!value.isObject() || value.size() != CURSOR_FIELDS.size()) throw new IllegalArgumentException();
            for (String field : CURSOR_FIELDS) if (!value.has(field)) throw new IllegalArgumentException();
            if (!value.get("version").isIntegralNumber() || value.get("version").asInt() != 1
                    || !value.get("id").isIntegralNumber() || !value.get("id").canConvertToLong() || value.get("id").asLong() < 1)
                throw new IllegalArgumentException();
            for (String field : List.of("tenantId", "userId", "q", "updatedAt"))
                if (!value.get(field).isString()) throw new IllegalArgumentException();
            if (!owner.tenantId().equals(value.get("tenantId").asString()) || !owner.userId().equals(value.get("userId").asString())
                    || !q.equals(value.get("q").asString())) throw new IllegalArgumentException();
            String timestamp = value.get("updatedAt").asString();
            LocalDateTime updatedAt = LocalDateTime.parse(timestamp);
            if (updatedAt.getYear() < 1000 || updatedAt.getYear() > 9999 || !updatedAt.toString().equals(timestamp)) throw new IllegalArgumentException();
            return new Position(updatedAt, value.get("id").asLong());
        } catch (RuntimeException invalid) {
            throw problem(400, "无效视觉记忆分页游标");
        }
    }

    private static void requireOwner(ChatIdentity owner) {
        if (owner == null || !owner.authenticated() || owner.tenantId() == null || owner.tenantId().isBlank()
                || owner.userId() == null || owner.userId().isBlank()) throw problem(401, "请先登录");
    }

    private static ResponseStatusException problem(int status, String detail) {
        return new ResponseStatusException(HttpStatus.valueOf(status), detail);
    }

    private record Position(LocalDateTime updatedAt, long id) {}
    private record Stored(long id, String content, String metadata, LocalDateTime updatedAt) {}
    public record Page(List<Prompt> items, String nextCursor) {}
    public record Prompt(String id, String title, String content, String category, String source, String updatedAt,
                         List<String> subjects, String palette, String composition, String mood, String sourceAssetId) {}
}
