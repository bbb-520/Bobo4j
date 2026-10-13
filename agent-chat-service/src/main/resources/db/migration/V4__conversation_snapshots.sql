-- Browser view state only. Execution, usage and financial records remain authoritative.
CREATE TABLE conversation_snapshot (
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    title VARCHAR(255) NOT NULL,
    snapshot_json LONGTEXT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id, conversation_id),
    KEY idx_snapshot_owner_updated (tenant_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
