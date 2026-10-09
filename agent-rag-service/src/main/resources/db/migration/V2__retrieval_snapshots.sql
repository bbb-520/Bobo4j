CREATE TABLE rag_stage_snapshot (
 tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL, composite_id VARCHAR(128) NOT NULL,
 stage VARCHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,user_id,composite_id,stage)
);
