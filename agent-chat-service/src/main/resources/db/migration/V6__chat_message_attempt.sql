ALTER TABLE chat_message
    ADD COLUMN attempt_id VARCHAR(128) NULL,
    ADD KEY idx_chat_message_attempt (conversation_db_id, attempt_id);
