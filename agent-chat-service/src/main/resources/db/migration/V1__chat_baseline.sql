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

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL PRIMARY KEY,
    conversation_db_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    completed TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    KEY idx_chat_message_conversation_created (conversation_db_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vision_memory (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      user_id VARCHAR(255) NULL,
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
    KEY idx_vision_memory_owner (user_id, tenant_id, active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
