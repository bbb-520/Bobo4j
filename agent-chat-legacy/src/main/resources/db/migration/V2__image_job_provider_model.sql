-- Compatibility-only migration retained for operators of the read-only legacy jar.
-- New services own image_job and must not execute this script.
ALTER TABLE image_job ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'QWEN';
ALTER TABLE image_job ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT 'qwen-image-3.0-pro';
