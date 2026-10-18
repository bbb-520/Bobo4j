# Image uncertainty and payment recovery controls

New IMAGE reservations are serialized under the user's existing wallet row lock. They are refused with a curated HTTP 429 after either limit is reached within the rolling window:

| Configuration | Default | Meaning |
| --- | --- | --- |
| `app.billing.image-unknown-limit` | `3` | Number of audited unknown IMAGE outcomes |
| `app.billing.image-unknown-window-seconds` | `900` | Rolling window based on the abandonment audit timestamp |
| `app.billing.image-unknown-exposure-prices` | `3` | Exposure budget in multiples of the currently configured image price |

Exposure is the sum of each abandoned request's snapshotted image price, including promotional calls. Both provider-unknown and execution-deadline abandonment count. Undispatched cancellations do not count. Settings must be positive. Existing RESERVED request replays precede the guard; text, embedding, reranking and evaluation reservations bypass it. Unknown images retain NULL token counts and their original audit reason, restore the wallet once, and cannot later settle or release a newer request's hold.

`app.payments.expired-uncertainty-seconds` defaults to `86400`. A still-PENDING order unresolved for that long after its expiry enters `reconcile_state=MANUAL` under its reconciliation lease, with audit reason `EXPIRED_PAYMENT_OUTCOME_UNKNOWN`. Its financial status, balance, QR and provider evidence are preserved. The scheduler and user refresh stop querying MANUAL orders. The check occurs before provider I/O and again when finishing a sync. A verified late notification can still credit real money exactly once; MANUAL remains explicit until an operator resumes.

Operators with the existing `billing:reconcile` permission use:

- `GET /api/billing/admin/payment-orders/{id}` to inspect the order without provider I/O.
- `POST /api/billing/admin/payment-orders/{id}/resume` with `{"reason":"review reference and reason"}` to resume automated queries. Browser session authentication, the existing admin permission policy, CSRF protection and a second server-side permission check all apply.
- `GET /api/payments/admin/orders/{id}/audit` includes reconciliation actions, actors, reasons and timestamps. The reconciliation report includes `reconcileState` and `reconcileResumedAt`.

Resume is serialized with notifications and reconciliation. Repeating it on an already resumed order has no side effects or extra audit entries. A resume records the actor/reason, makes safe queries due, and starts a fresh bounded uncertainty window. It performs no external call and does not replay prepay or refund POSTs. Active leases and incompatible financial states return 409. Existing V11 refund-attempt rules remain unchanged.

Deploy the additive V12 migration before starting this version. Validation uses transactional H2/MySQL-mode storage, concurrency tests, the real browser security chain and signed local Alipay fixtures. Actual MySQL migration and provider availability require release validation.
