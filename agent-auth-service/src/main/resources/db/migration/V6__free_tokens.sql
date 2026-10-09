-- Existing accounts retain their promotions; only new registration grants token credit.
ALTER TABLE billing_wallet ADD free_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing_wallet ADD CONSTRAINT ck_wallet_free_tokens CHECK (free_tokens >= 0);
ALTER TABLE model_usage ADD free_tokens_used BIGINT NOT NULL DEFAULT 0;
ALTER TABLE model_usage ADD CONSTRAINT ck_usage_free_tokens CHECK (free_tokens_used >= 0);
