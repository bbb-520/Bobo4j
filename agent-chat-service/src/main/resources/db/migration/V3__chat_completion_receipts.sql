CREATE TABLE chat_completion (
 call_id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, tenant_id VARCHAR(128) NOT NULL,
 parameter_hash VARCHAR(64) NOT NULL, model_user LONGTEXT NOT NULL, status VARCHAR(16) NOT NULL,
 receipt LONGTEXT NULL, history_committed BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_chat_completion_owner ON chat_completion(user_id,tenant_id,created_at);
