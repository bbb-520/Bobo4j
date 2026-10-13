-- Legacy or incompatible vectors remain durable, but are excluded until owner-authorized reindexing.
ALTER TABLE vision_memory ADD COLUMN embedding_fingerprint VARCHAR(64) NULL;
ALTER TABLE vision_memory ADD COLUMN embedding_dimensions INT NULL;
CREATE INDEX idx_vision_vector_space ON vision_memory(tenant_id,user_id,active,embedding_fingerprint,embedding_dimensions);
