-- Orchestrator 乐观锁版本列（设计文档 §13）。
-- 只做新增列，不改动任何已存在的列或历史迁移。
ALTER TABLE agent_run ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
