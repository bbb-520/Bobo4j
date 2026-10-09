CREATE TABLE billing_wallet (
    user_id VARCHAR(64) PRIMARY KEY,
    balance_micros BIGINT NOT NULL DEFAULT 0,
    reserved_micros BIGINT NOT NULL DEFAULT 0,
    free_images INT NOT NULL DEFAULT 0,
    active_usage_id VARCHAR(128) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_wallet_reserved CHECK (reserved_micros >= 0),
    CONSTRAINT ck_wallet_free CHECK (free_images >= 0)
);
CREATE TABLE model_usage (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    capability VARCHAR(16) NOT NULL,
    model VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    trial BOOLEAN NOT NULL,
    reserved_micros BIGINT NOT NULL,
    rate_per_1k_micros BIGINT NOT NULL,
    image_price_micros BIGINT NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    metering VARCHAR(16) NULL,
    charged_micros BIGINT NOT NULL DEFAULT 0,
    provider_request_id VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_usage_wallet FOREIGN KEY (user_id) REFERENCES billing_wallet(user_id)
);
CREATE INDEX idx_usage_user_created ON model_usage(user_id, created_at);
CREATE TABLE payment_order (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider_trade_id VARCHAR(128) NULL,
    qr_code TEXT NULL,
    prepay_owner VARCHAR(36) NULL,
    prepay_until TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at TIMESTAMP(3) NOT NULL,
    paid_at TIMESTAMP(3) NULL,
    CONSTRAINT uk_payment_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_payment_trade UNIQUE (channel, provider_trade_id),
    CONSTRAINT fk_order_wallet FOREIGN KEY (user_id) REFERENCES billing_wallet(user_id)
);
CREATE INDEX idx_order_user_created ON payment_order(user_id, created_at);
CREATE TABLE usage_reconciliation (
 usage_id VARCHAR(128) NOT NULL,
 action VARCHAR(16) NOT NULL,
 actor VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(usage_id,action),
 CONSTRAINT fk_reconciliation_usage FOREIGN KEY (usage_id) REFERENCES model_usage(id)
);
