CREATE TABLE IF NOT EXISTS agent_run (
    id CHAR(36) NOT NULL PRIMARY KEY,
    workflow VARCHAR(64) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    input_text LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_agent_run_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
