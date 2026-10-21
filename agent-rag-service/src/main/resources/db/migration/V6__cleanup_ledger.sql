-- F12：跨存储清理账本。
--
-- 删除一个文档要清三处：元数据/分块（MySQL）、原件与工作目录（文件系统）、向量（Milvus）。
-- 三者没有共同事务，删除请求又可能正好撞上进行中的索引写入，于是"清完又被写回来"是必然发生的，
-- 不是异常。光靠一次同步清理无法收敛，需要把清理意图持久化下来，由后台按延迟重扫直到确认干净。
--
-- 设计要点：
-- 1. 一文档一行，主键即 document_id；owner 一并落库，重扫时不依赖调用方上下文，也保证不会跨 owner。
-- 2. next_attempt_at 是租约式调度：扫描器只取到期行，加锁后推进 next_attempt_at，
--    多实例同时扫也不会对同一文档重复清理。
-- 3. attempts 有上限，超限转 GAVE_UP 并保留 last_error，交给人工——静默重试到天荒地老同样是失真。
CREATE TABLE rag_cleanup_ledger (
    document_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY rag_cleanup_due (state, next_attempt_at)
);
