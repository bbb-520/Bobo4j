CREATE TABLE rag_document (
 document_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 request_id VARCHAR(128) NOT NULL, filename VARCHAR(512) NOT NULL, content_hash CHAR(64) NOT NULL,
 storage_path VARCHAR(1024) NOT NULL, status VARCHAR(40) NOT NULL, index_status VARCHAR(40) NOT NULL,
 summary_status VARCHAR(40) NOT NULL, index_version INT NOT NULL DEFAULT 0, processing_version INT NOT NULL DEFAULT 1,
 chunk_count INT NOT NULL DEFAULT 0, completed_chunks INT NOT NULL DEFAULT 0, error VARCHAR(128), deleted BOOLEAN NOT NULL DEFAULT FALSE,
 summary_text LONGTEXT, summary_covered INT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE KEY rag_upload_request(tenant_id,user_id,request_id), KEY rag_owner(tenant_id,user_id,deleted)
);
CREATE TABLE rag_job (
 document_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 index_version INT NOT NULL, stage VARCHAR(40) NOT NULL, fence BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP(6), parsed_path VARCHAR(1024), available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), KEY rag_job_lease(available_at,lease_until,stage)
);
CREATE TABLE rag_chunk (
 chunk_id CHAR(64) PRIMARY KEY, document_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 index_version INT NOT NULL, text LONGTEXT NOT NULL, title TEXT NOT NULL, page INT, source_ordinal INT NOT NULL,
 char_start INT NOT NULL, char_end INT NOT NULL, content_hash CHAR(64) NOT NULL, vector_json LONGTEXT,
 indexed BOOLEAN NOT NULL DEFAULT FALSE, KEY rag_chunk_scope(tenant_id,user_id,document_id,index_version,source_ordinal)
);
CREATE TABLE rag_embedding_cache (
 cache_key CHAR(64) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 model VARCHAR(128) NOT NULL, dimensions INT NOT NULL, processing_version VARCHAR(128) NOT NULL,
 vector_json LONGTEXT NOT NULL, call_id VARCHAR(128) NOT NULL, input_tokens BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE rag_summary_node (
 document_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 index_version INT NOT NULL, node_id VARCHAR(128) NOT NULL, prompt_version VARCHAR(64) NOT NULL,
 first_chunk INT NOT NULL, last_chunk INT NOT NULL, text LONGTEXT NOT NULL, call_id VARCHAR(128) NOT NULL,
 input_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL, PRIMARY KEY(document_id,index_version,node_id,prompt_version)
);
CREATE TABLE rag_conversation (
 conversation_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 versions_json TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), KEY rag_conversation_owner(tenant_id,user_id)
);
CREATE TABLE rag_answer (
 call_id VARCHAR(128) NOT NULL, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 conversation_id VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, question TEXT NOT NULL,
 status VARCHAR(40) NOT NULL, fence BIGINT NOT NULL DEFAULT 1, lease_until TIMESTAMP(6), answer_json LONGTEXT,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(tenant_id,user_id,call_id), KEY rag_history(conversation_id,status,updated_at)
);
CREATE TABLE rag_trace (
 trace_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 conversation_id VARCHAR(64) NOT NULL, call_id VARCHAR(128), phase VARCHAR(40) NOT NULL,
 payload_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), KEY rag_trace_owner(tenant_id,user_id,conversation_id)
);
CREATE TABLE rag_model_call (
 tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL, call_id VARCHAR(128) NOT NULL,
 composite_id VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, capability VARCHAR(32) NOT NULL,
 status VARCHAR(40) NOT NULL, receipt_json LONGTEXT, input_tokens BIGINT, output_tokens BIGINT,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,user_id,call_id), KEY rag_composite(tenant_id,user_id,composite_id)
);
