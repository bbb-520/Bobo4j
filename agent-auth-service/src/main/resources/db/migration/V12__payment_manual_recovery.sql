ALTER TABLE payment_order ADD COLUMN reconcile_resumed_at TIMESTAMP(3) NULL;
CREATE TABLE payment_reconciliation_audit (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL,
    action VARCHAR(24) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_reconciliation_order FOREIGN KEY(order_id) REFERENCES payment_order(id)
);
CREATE INDEX idx_payment_reconciliation_order ON payment_reconciliation_audit(order_id,created_at);
CREATE INDEX idx_image_expense_guard ON model_usage(user_id,capability,status);
