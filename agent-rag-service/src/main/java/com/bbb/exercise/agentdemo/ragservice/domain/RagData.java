package com.bbb.exercise.agentdemo.ragservice.domain;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import java.util.List;
import java.util.Map;

/** RAG 内部领域数据类型集合，统一解析、分块、检索范围和入库任务的表示。 */
public final class RagData {
    private RagData() {}

    /** 文档解析块，记录标题、正文、块类型及原始字符和页码位置。 */
    public record Block(
            String blockId,
            String kind,
            String title,
            String text,
            int ordinal,
            int charStart,
            int charEnd,
            Integer page) {}

    /** 可检索文档分块，关联文档版本、原始位置、内容哈希及嵌入空间。 */
    public record Chunk(
            String id,
            String documentId,
            int version,
            String text,
            String title,
            Integer page,
            int ordinal,
            int charStart,
            int charEnd,
            String hash,
            String embeddingFingerprint) {
        public Chunk(
                String id,
                String documentId,
                int version,
                String text,
                String title,
                Integer page,
                int ordinal,
                int charStart,
                int charEnd,
                String hash) {
            this(
                    id,
                    documentId,
                    version,
                    text,
                    title,
                    page,
                    ordinal,
                    charStart,
                    charEnd,
                    hash,
                    null);
        }
    }

    /** 允许检索的文档范围，将可信身份、固定索引版本和嵌入空间绑定。 */
    public record Scope(
            ChatIdentity identity, Map<String, Integer> versions, String embeddingFingerprint) {
        public Scope(ChatIdentity identity, Map<String, Integer> versions) {
            this(identity, versions, null);
        }

        public Scope {
            versions = Map.copyOf(versions);

            if (versions.isEmpty()) {
                throw new IllegalArgumentException("Empty document scope");
            }
        }
    }

    /** 向量或关键词召回命中，保留所属租户、用户及文档版本以便过滤。 */
    public record Hit(
            String chunkId,
            String documentId,
            int version,
            String tenant,
            String user,
            double score) {}

    /** 文档入库任务的租约快照，记录处理阶段、文件位置和隔离旧工作节点的代次。 */
    public record Job(
            String id,
            ChatIdentity identity,
            int version,
            long fence,
            String stage,
            String path,
            String filename) {}

    /** 文档解析结果，汇总结构块、媒体类型和提取的字符数量。 */
    public record Parsed(List<Block> blocks, String mediaType, int characters) {}
}
