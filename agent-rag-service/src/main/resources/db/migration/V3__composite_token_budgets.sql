CREATE TABLE rag_call_budget (
 tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL, composite_id VARCHAR(128) NOT NULL,
 token_limit BIGINT NOT NULL, actual_tokens BIGINT NOT NULL DEFAULT 0, held_tokens BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(tenant_id,user_id,composite_id)
);
ALTER TABLE rag_model_call ADD COLUMN reserved_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_model_call ADD COLUMN budget_settled BOOLEAN NOT NULL DEFAULT FALSE;
