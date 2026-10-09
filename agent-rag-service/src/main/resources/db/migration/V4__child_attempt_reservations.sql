-- Keep the new generation reservation separate from older unknown token usage across crashes.
ALTER TABLE rag_model_call ADD COLUMN attempt_reservation BIGINT NOT NULL DEFAULT 0;
