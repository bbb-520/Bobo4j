CREATE TABLE agent_execution (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 request_id VARCHAR(128) NOT NULL, payload_hash VARCHAR(64) NOT NULL, type VARCHAR(32) NOT NULL,
 question TEXT NOT NULL, input_json LONGTEXT NOT NULL, status VARCHAR(48) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, last_seq BIGINT NOT NULL DEFAULT 0,
 generation_version INT NOT NULL DEFAULT 1, input_revision INT NOT NULL DEFAULT 1, budget_json TEXT NOT NULL,
 answer LONGTEXT NULL, sources_json LONGTEXT NULL, error VARCHAR(500) NULL,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, lease_owner VARCHAR(64) NULL,
 lease_token VARCHAR(36) NULL, lease_until TIMESTAMP(3) NULL,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 CONSTRAINT uk_execution_request UNIQUE (tenant_id,user_id,request_id)
);
CREATE INDEX idx_execution_work ON agent_execution(status,lease_until,created_at);
CREATE TABLE agent_execution_step (
 id VARCHAR(36) PRIMARY KEY, execution_id VARCHAR(36) NOT NULL,
 step_key VARCHAR(128) NOT NULL, parameter_hash VARCHAR(64) NOT NULL, call_id VARCHAR(128) NOT NULL,
 status VARCHAR(32) NOT NULL, result_json LONGTEXT NULL, active_millis BIGINT NOT NULL DEFAULT 0,
 reserved_tokens BIGINT NOT NULL DEFAULT 0, unknown_tokens BIGINT NOT NULL DEFAULT 0,
 accounted_input_tokens BIGINT NOT NULL DEFAULT 0, accounted_output_tokens BIGINT NOT NULL DEFAULT 0,
 target VARCHAR(16) NULL, request_path VARCHAR(500) NULL, request_json LONGTEXT NULL,
 consumption VARCHAR(16) NULL, tool_name VARCHAR(64) NULL,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 CONSTRAINT uk_execution_step UNIQUE(execution_id,step_key),
 CONSTRAINT fk_execution_step FOREIGN KEY(execution_id) REFERENCES agent_execution(id)
);
CREATE TABLE agent_execution_event (
 execution_id VARCHAR(36) NOT NULL, seq BIGINT NOT NULL, type VARCHAR(48) NOT NULL,
 generation_version INT NOT NULL, payload_json LONGTEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(execution_id,seq),
 CONSTRAINT fk_execution_event FOREIGN KEY(execution_id) REFERENCES agent_execution(id)
);
CREATE TABLE agent_execution_resume (
 execution_id VARCHAR(36) NOT NULL, request_id VARCHAR(128) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(execution_id,request_id),
 CONSTRAINT fk_execution_resume FOREIGN KEY(execution_id) REFERENCES agent_execution(id)
);
CREATE TABLE agent_execution_checkpoint (
 execution_id VARCHAR(36) PRIMARY KEY, version BIGINT NOT NULL, state_json LONGTEXT NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL,
 CONSTRAINT fk_execution_checkpoint FOREIGN KEY(execution_id) REFERENCES agent_execution(id)
);
CREATE TABLE agent_execution_outbox (
 id VARCHAR(36) PRIMARY KEY, execution_id VARCHAR(36) NOT NULL, event_seq BIGINT NOT NULL,
 payload_json LONGTEXT NOT NULL, status VARCHAR(16) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 CONSTRAINT uk_execution_outbox UNIQUE(execution_id,event_seq),
 CONSTRAINT fk_execution_outbox FOREIGN KEY(execution_id) REFERENCES agent_execution(id)
);
