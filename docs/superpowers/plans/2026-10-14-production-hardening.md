# Production hardening implementation plan

> **For agentic workers:** Use superpowers:executing-plans within each independent subsystem; follow systematic-debugging, TDD and verification-before-completion. Track completion below.

**Goal:** Publish a consistent application version and close the confirmed recovery, financial control, security, monitoring and backup gaps.

**Architecture:** Preserve the existing service/API ownership boundaries and durable billing receipts. Validate independent auth and media fixes in isolated source snapshots, integrate into the existing publication clones, then release all applications with a manifest and rollback checkpoints. Operations changes are staged and checked before reload; no unknown payment or model outcome is fabricated.

**Tech stack:** Java 21, Maven, Spring Boot/WebFlux/Spring AI, MySQL, Redis, Milvus, React/TypeScript, Docker Compose, Prometheus/Alertmanager, OSS.

**Spec:** Local assessment `.migration/project-assessment-20261014.md` and user instruction “开始执行”. Alert recipient: supplied privately by the operator. Backup destination: existing OSS bucket, independent private prefix, encrypted before upload.

## Constraints and review focus

- Preserve account ownership, stable paid request IDs, actual usage and refund audit. No automatic replay of uncertain paid POSTs; no invented zero usage; no unknown payment forcibly marked unpaid.
- Keep current production and rollback images, database snapshots, user documents and keys. Never log secret env values, private keys, signed URLs or raw provider payloads.
- Reuse existing clean publication clones; original workspaces have unrelated edits. Sync changed files only after baseline comparison and backup.
- Maven runs in isolated snapshots may use the shared read-only dependency cache, but must not install divergent SNAPSHOT artifacts. Require elevated execution for Java/Mockito and frontend builds.
- Review process death, expired leases, concurrent queue admission, duplicate/late billing events, and extended payment uncertainty with explicit tests.

## Tasks

- [ ] **Auth/payment recovery and image expense guard.** Modify BillingService and PaymentService/PaymentReconciliation, add focused tests. Default image unknown outcome guard: at most 3 failures in 15 minutes and estimated exposure budget 3 image prices; applies only to new IMAGE calls. Existing request replays and text/document operations remain recoverable. Expired payment orders repeatedly failing for 24 hours enter MANUAL reconciliation without being marked paid/unpaid, with an audited admin resume action. RED -> GREEN, then report exact changed files.
- [ ] **Image task terminal states and bounded worker queue.** Modify ImageJobService/Worker and focused tests. Expired PROCESSING with an expired/missing lease reaches a terminal state; active valid leases and durable provider receipts are respected. Add configurable bounded parallel workers, per-user queue admission (default 2) and global pending cap (default 20), with race-safe database admission. User rejection/circuit-open must not dispatch providers. RED -> GREEN, then report exact changed files.
- [ ] **Unified source release and CI.** Integrate fixes, build full backend/frontend, add repository CI and a manifest gate recording source revisions, image digests and migration versions. Add browser smoke coverage and non-paid queue/API load fixtures. Run full required verification and independent review. Push task branches and sync originals safely.
- [ ] **Monitoring and alerts.** Add Alertmanager with existing SMTP credentials, supplied recipient, private host binding and explicit real notification acceptance. Add rules for process health/memory, recovery/queue backlog, payment MANUAL/error and image unknown costs. Keep secrets in local/server protected files, not Git.
- [ ] **Encrypted scheduled backups and restore validation.** Use independent private OSS prefix; encrypted archives contain MySQL dump, required configuration and consistent document/vector backup strategy. Keep encryption key separately with protected local recovery copy. Validate decrypt/restore into isolated resources before enabling timer; upload integrity and retention checks required.
- [ ] **Server hardening and resource budgets.** Verify existing SSH public-key access and an independently usable management path before any authentication restriction. Stage/reload reversible SSH and Nginx headers. Check actual NFS mounts/exports/dependencies before disabling unused NFS/RPC. Stage Nacos authentication and memory/client settings together. Never rotate credentials without a verified recovery path and protected delivery.
- [ ] **Production rollout and acceptance.** Back up consistent state, deploy sequentially using fixed runtime base images rather than chaining old application images; respect resource limits and active executions. Validate migration status, cloud sync, route limiting, RAG, image success/recovery, actual delivery and billing idempotency with isolated fixtures. Keep gallery disabled until explicitly imported/verified; no blanket claim of production semantic readiness.
- [ ] **Follow-on retrieval/UI scalability.** Improve cloud-history pagination/conflict recovery and modularize frontend recovery logic, establish retrieval quality fixtures/OCR boundaries and document what remains external or unverified. Do not replace already-tested flows wholesale.

## Completion evidence

Record commands/results and rulings in `.migration/production-hardening-ledger.md`; maintain deployment manifest and operator README. Any external dependency that cannot be validated stays explicitly pending while independent work continues.
