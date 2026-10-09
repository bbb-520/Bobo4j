ALTER TABLE rag_document ADD COLUMN embedding_fingerprint CHAR(64);
ALTER TABLE rag_job ADD COLUMN embedding_fingerprint CHAR(64);
ALTER TABLE rag_chunk ADD COLUMN embedding_fingerprint CHAR(64);
ALTER TABLE rag_embedding_cache ADD COLUMN model_fingerprint CHAR(64);
