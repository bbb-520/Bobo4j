CREATE TABLE model_call_recovery (
 group_id VARCHAR(128) NOT NULL, generation INT NOT NULL, role VARCHAR(16) NOT NULL,
 action VARCHAR(24) NOT NULL, reason VARCHAR(128) NOT NULL, estimated_exposure_micros BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(group_id,generation,role,action),
 CONSTRAINT fk_recovery_group FOREIGN KEY(group_id) REFERENCES model_call_group(id)
);
CREATE INDEX idx_group_recovery ON model_call_group(status,updated_at);
CREATE INDEX idx_image_usage_recovery ON model_usage(status,updated_at);
