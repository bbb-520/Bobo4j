CREATE TABLE zine_generation_receipt (
 id VARCHAR(128) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 user_id VARCHAR(64) NOT NULL,
 response_json TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_zine_receipt_owner ON zine_generation_receipt(tenant_id,user_id,created_at);
