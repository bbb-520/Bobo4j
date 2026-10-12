ALTER TABLE payment_order ADD COLUMN qr_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_order ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_order ADD COLUMN qr_updated_at TIMESTAMP(3) NULL;
ALTER TABLE payment_order ADD COLUMN provider_status VARCHAR(32) NULL;
ALTER TABLE payment_order ADD COLUMN receipt_cents BIGINT NULL;
ALTER TABLE payment_order ADD COLUMN buyer_paid_cents BIGINT NULL;
ALTER TABLE payment_order ADD COLUMN invoice_cents BIGINT NULL;
ALTER TABLE payment_order ADD COLUMN provider_refunded_cents BIGINT NULL;
ALTER TABLE payment_order ADD COLUMN last_checked_at TIMESTAMP(3) NULL;
ALTER TABLE payment_order ADD COLUMN reconcile_state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE payment_refund ADD COLUMN checks INT NOT NULL DEFAULT 0;
ALTER TABLE payment_refund ADD COLUMN resolved_by VARCHAR(64) NULL;
ALTER TABLE payment_refund ADD COLUMN resolution_reason VARCHAR(500) NULL;
ALTER TABLE payment_refund ADD COLUMN evidence_reference VARCHAR(256) NULL;
CREATE TABLE payment_ledger (
    event_key VARCHAR(96) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL,
    refund_id VARCHAR(32) NULL,
    event_type VARCHAR(32) NOT NULL,
    delta_micros BIGINT NOT NULL,
    actor VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_ledger_order FOREIGN KEY(order_id) REFERENCES payment_order(id)
);
CREATE INDEX idx_payment_ledger_order ON payment_ledger(order_id,created_at);
CREATE TABLE payment_observation (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL,
    source VARCHAR(24) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    provider_trade_id VARCHAR(128) NULL,
    amount_cents BIGINT NOT NULL,
    receipt_cents BIGINT NULL,
    buyer_paid_cents BIGINT NULL,
    invoice_cents BIGINT NULL,
    refunded_cents BIGINT NULL,
    app_id VARCHAR(64) NULL,
    seller_id VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_observation_order FOREIGN KEY(order_id) REFERENCES payment_order(id)
);
CREATE INDEX idx_payment_observation_order ON payment_observation(order_id,created_at);
