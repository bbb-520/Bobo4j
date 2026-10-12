ALTER TABLE payment_order ADD COLUMN sync_owner VARCHAR(36) NULL;
ALTER TABLE payment_order ADD COLUMN sync_until TIMESTAMP(3) NULL;
ALTER TABLE payment_order ADD COLUMN next_check_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE payment_order ADD COLUMN last_error VARCHAR(256) NULL;
CREATE INDEX idx_payment_recovery ON payment_order(channel,status,next_check_at);

CREATE TABLE payment_refund (
    id VARCHAR(32) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    sync_owner VARCHAR(36) NULL,
    sync_until TIMESTAMP(3) NULL,
    next_check_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(256) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES payment_order(id)
);
CREATE INDEX idx_refund_recovery ON payment_refund(status,next_check_at);
