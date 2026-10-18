-- Zine composes in Content; its durable execution and receipts are owned by Media.
ALTER TABLE image_job ADD COLUMN provider_dispatch_started BOOLEAN NOT NULL DEFAULT FALSE;
-- Existing PROCESSING jobs may already have reached the provider before this migration.
-- Fail closed for ambiguous in-flight work; result_ready receipts still recover normally.
UPDATE image_job SET provider_dispatch_started=TRUE WHERE attempt_count>0 OR status IN ('PROCESSING','FAILED','SUCCEEDED') OR result_ready=TRUE;
CREATE TABLE zine_media_request (
    job_id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    source_reference LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE (tenant_id,user_id,request_id)
);
