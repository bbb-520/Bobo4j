-- A single transaction guard serializes pending admission and per-owner worker claims across replicas.
-- No provider, wallet or object-storage calls take place while this row is locked.
CREATE TABLE image_job_admission_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO image_job_admission_guard(id) VALUES (1);

-- Queue policy now permits two pending jobs per owner, while claims allow one live job per owner.
DROP INDEX uk_image_job_active_owner ON image_job;
ALTER TABLE image_job DROP COLUMN active_slot;
CREATE INDEX idx_image_job_pending_owner ON image_job(status, tenant_id, user_id);
