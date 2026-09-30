ALTER TABLE image_job
    ADD COLUMN lease_owner VARCHAR(128) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD KEY idx_image_job_lease (status, lease_until);
