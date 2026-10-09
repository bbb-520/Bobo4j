CREATE TABLE model_call_group (
 id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(128) NOT NULL,
 parameter_hash VARCHAR(64) NOT NULL, capability VARCHAR(16) NOT NULL, foreground BOOLEAN NOT NULL,
 status VARCHAR(24) NOT NULL, generation INT NOT NULL DEFAULT 1, reserved_micros BIGINT NOT NULL DEFAULT 0,
 rate_per_1k_micros BIGINT NOT NULL, receipt LONGTEXT NULL, winning_role VARCHAR(16) NULL,
 charged_micros BIGINT NOT NULL DEFAULT 0, risk_micros BIGINT NOT NULL DEFAULT 0,risk_attempts INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_group_wallet FOREIGN KEY (user_id) REFERENCES billing_wallet(user_id)
);
CREATE INDEX idx_group_admission ON model_call_group(status,foreground,created_at);
CREATE TABLE model_call_attempt (
 group_id VARCHAR(128) NOT NULL, role VARCHAR(16) NOT NULL, provider VARCHAR(32) NOT NULL,
 model VARCHAR(128) NOT NULL, endpoint VARCHAR(500) NOT NULL, credential_id VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, planned_token_upper BIGINT NOT NULL DEFAULT 0,reserved_cost_micros BIGINT NOT NULL DEFAULT 0, input_tokens BIGINT NULL, output_tokens BIGINT NULL,
 provider_request_id VARCHAR(128) NULL, receipt LONGTEXT NULL, cost_micros BIGINT NULL,
 cost_owner VARCHAR(16) NOT NULL DEFAULT 'USER',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(group_id,role), CONSTRAINT fk_attempt_group FOREIGN KEY (group_id) REFERENCES model_call_group(id)
);
CREATE TABLE model_fault_budget (
 id INT PRIMARY KEY, allocated_micros BIGINT NOT NULL DEFAULT 0, unresolved INT NOT NULL DEFAULT 0,
 window_started TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), window_spent_micros BIGINT NOT NULL DEFAULT 0
);
INSERT INTO model_fault_budget(id) VALUES (1);
CREATE TABLE model_call_attempt_history (
 group_id VARCHAR(128) NOT NULL, generation INT NOT NULL, role VARCHAR(16) NOT NULL,
 provider VARCHAR(32) NOT NULL, model VARCHAR(128) NOT NULL, endpoint VARCHAR(500) NOT NULL,
 credential_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, planned_token_upper BIGINT NOT NULL DEFAULT 0,reserved_cost_micros BIGINT NOT NULL DEFAULT 0, input_tokens BIGINT NULL,
 output_tokens BIGINT NULL, provider_request_id VARCHAR(128) NULL, receipt LONGTEXT NULL,
 cost_micros BIGINT NULL, cost_owner VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(group_id,generation,role)
);
CREATE TABLE model_call_reconciliation (
 group_id VARCHAR(128) NOT NULL, generation INT NOT NULL DEFAULT 1, role VARCHAR(16) NOT NULL, actor VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL, cost_micros BIGINT NOT NULL,no_usage_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(group_id,generation,role)
);
CREATE TABLE model_call_token_reconciliation (
 group_id VARCHAR(128) NOT NULL,generation INT NOT NULL,role VARCHAR(16) NOT NULL,
 input_tokens BIGINT NOT NULL,output_tokens BIGINT NOT NULL,actor VARCHAR(64) NOT NULL,reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(group_id,generation,role)
);
