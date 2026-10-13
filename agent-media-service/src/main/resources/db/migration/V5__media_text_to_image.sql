-- Pure text creation has no source asset. Client IDs make network retries idempotent.
ALTER TABLE image_job
    MODIFY COLUMN source_asset_id CHAR(36) NULL,
    MODIFY COLUMN source_object_key VARCHAR(512) NULL,
    ADD COLUMN client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_image_job_client_request (tenant_id, user_id, client_request_id);
