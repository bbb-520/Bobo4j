package com.bbb.exercise.agentdemo1_0.memory;

import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.generation.SceneCard;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Hybrid RAG memory: durable MySQL records with deterministic lexical retrieval. */
@Service
@RequiredArgsConstructor
public class VisionMemoryService {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public void rememberScene(ChatIdentity identity, SceneCard card, String instruction) {
        if (card == null || card.summary() == null || card.summary().isBlank()) return;
        long userId = auth.requireUserId(identity);
        String content = card.summary() + " " + String.join(" ", card.subjects()) + " "
                + card.palette() + " " + card.composition() + " " + card.mood()
                + (instruction == null ? "" : " 用户指令：" + instruction);
        jdbc.update("INSERT INTO vision_memory(user_id,tenant_id,kind,content,structured_json,source,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)", userId, identity.tenantId(), "VISUAL_PREFERENCE", content,
                structured(card), "SCENE_CARD", LocalDateTime.now(), LocalDateTime.now());
    }

    public String augment(ChatIdentity identity, String prompt, int limit) {
        if (prompt == null || prompt.isBlank()) return prompt;
        long userId = auth.requireUserId(identity);
        List<Memory> memories = jdbc.query("SELECT id,kind,content,UNIX_TIMESTAMP(updated_at) FROM vision_memory "
                        + "WHERE user_id=? AND tenant_id=? AND active=1 ORDER BY updated_at DESC LIMIT 100",
                (rs, rowNum) -> new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                userId, identity.tenantId());
        String context = memories.stream().sorted(Comparator.comparingDouble((Memory m) -> score(prompt, m)).reversed())
                .limit(Math.max(1, Math.min(limit, 8))).map(Memory::content).reduce((a, b) -> a + "；" + b).orElse("");
        if (context.isBlank()) return prompt;
        return prompt + "\n\n【长期视觉偏好参考，仅在相关时采用】\n" + context;
    }

    public static double score(String query, Memory memory) {
        String q = query.toLowerCase(Locale.ROOT);
        String c = memory.content().toLowerCase(Locale.ROOT);
        var matcher = TOKEN.matcher(q);
        int overlap = 0;
        while (matcher.find()) if (c.contains(matcher.group())) overlap++;
        return overlap * 10.0 + Math.min(5.0, memory.updatedAtEpoch() / 1_000_000_000d / 365d);
    }

    private static String structured(SceneCard card) {
        return "{\"palette\":\"" + escape(card.palette()) + "\",\"composition\":\""
                + escape(card.composition()) + "\",\"mood\":\"" + escape(card.mood()) + "\"}";
    }

    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public record Memory(Long id, String kind, String content, long updatedAtEpoch) {}
}
