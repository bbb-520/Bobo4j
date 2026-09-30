CREATE DATABASE IF NOT EXISTS bobo_db
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE bobo_db;

CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGINT NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_chat_conversation_owner (tenant_id(64), user_id(64), conversation_id),
    KEY idx_chat_conversation_user_updated (tenant_id(64), user_id(64), updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS image_asset (
    id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_image_asset_object_key (object_key(191)),
    KEY idx_image_asset_owner (tenant_id(64), user_id(64), created_at),
    KEY idx_image_asset_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS image_job (
    id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    source_asset_id CHAR(36) NOT NULL,
    source_object_key VARCHAR(512) NOT NULL,
    output_object_key VARCHAR(512) NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'gathered',
    language VARCHAR(32) NOT NULL DEFAULT 'chinese',
    prompt TEXT NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'QWEN',
    model VARCHAR(128) NOT NULL DEFAULT 'qwen-image-3.0-pro',
    rationale TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    provider_request_id VARCHAR(255) NULL,
    error_message TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY idx_image_job_owner (tenant_id(64), user_id(64), created_at),
    KEY idx_image_job_conversation (tenant_id(64), user_id(64), conversation_id, created_at),
    KEY idx_image_job_queue (status, created_at),
    CONSTRAINT fk_image_job_asset FOREIGN KEY (source_asset_id) REFERENCES image_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bobo_world_item (
    id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    source_job_id CHAR(36) NOT NULL,
    image_object_key VARCHAR(512) NOT NULL,
    caption VARCHAR(500) NULL,
    anonymous TINYINT(1) NOT NULL DEFAULT 1,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    display_name_snapshot VARCHAR(64) NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    cleanup_pending TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_bobo_world_owner_job (tenant_id(64), user_id(64), source_job_id),
    KEY idx_bobo_world_public (visibility, status, created_at, id),
    KEY idx_bobo_world_owner (tenant_id(64), user_id(64), created_at, id),
    KEY idx_bobo_world_cleanup (status, cleanup_pending, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_auth_session_token (token_hash),
    KEY idx_auth_session_expiry (expires_at),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_api_key (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    qwen_api_key_ciphertext TEXT NULL,
    tavily_api_key_ciphertext TEXT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_api_key_user (user_id),
    CONSTRAINT fk_user_api_key_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_model_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    capability VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    api_key_ciphertext TEXT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_model_capability (user_id, capability),
    KEY idx_user_model_provider (provider),
    CONSTRAINT fk_user_model_profile_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL PRIMARY KEY,
    conversation_db_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    completed TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    KEY idx_chat_message_conversation_created (conversation_db_id, created_at, id),
    CONSTRAINT fk_chat_message_conversation
        FOREIGN KEY (conversation_db_id) REFERENCES chat_conversation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vision_memory (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    structured_json JSON NULL,
    embedding_json JSON NULL,
    source VARCHAR(64) NOT NULL,
    recalled_count INT NOT NULL DEFAULT 0,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_vision_memory_owner (user_id, tenant_id, active, updated_at),
    CONSTRAINT fk_vision_memory_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
