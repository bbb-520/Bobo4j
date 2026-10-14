# Production recovery and operations

The 2026-10-14 upgrade consolidates the previously separate architecture/image recovery branches. New image jobs have race-safe global/per-owner pending admission and bounded workers; expired jobs without valid owned leases converge while durable late receipts remain recoverable. IMAGE reservations also have an audited rolling unknown-outcome/exposure guard. Text and document capabilities are excluded from that image-specific guard; a real active wallet reservation still protects concurrent settlement.

See [auth recovery controls](../agent-auth-service/docs/recovery-controls.md) for exact cost thresholds and audited payment MANUAL/resume APIs. A timeout or unavailable supplier never proves an order unpaid. Resume only schedules safe queries; it does not repeat prepay/refund POSTs. Real Alipay payment/refund acceptance remains distinct from signed local callback tests.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `app.image-jobs.parallelism` | 2 | Immediately runnable worker slots; no in-memory backlog |
| `app.image-jobs.per-user-pending-limit` | 2 | Per tenant/user queued+processing limit |
| `app.image-jobs.global-pending-limit` | 20 | Global queued+processing limit |
| `app.billing.image-unknown-limit` | 3 | Audited unknown IMAGE outcomes allowed per rolling window |
| `app.billing.image-unknown-window-seconds` | 900 | Guard window |
| `app.billing.image-unknown-exposure-prices` | 3 | Estimated risk budget in configured image prices |
| `app.payments.expired-uncertainty-seconds` | 86400 | Overdue uncertainty moves to manual reconciliation |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | deployment:6 | Bound each application connection pool |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | deployment:1 | Avoid keeping unnecessary idle connections |

Spring properties can be supplied by environment using their uppercase underscore form, e.g. `APP_IMAGE_JOBS_PARALLELISM=2`. Keep secrets in protected deployment files, not README, Git or shell arguments. Pool/queue defaults are conservative policies for the current single host, not a proven throughput guarantee. Increasing worker or pool limits requires CPU/memory/provider/SQL measurements together.

Pending limits govern new submissions at admission. An already paid task with a durable late receipt is allowed to recover delivery past expiry or at capacity without calling the supplier again. Such recovery may temporarily put total pending counts above the admission limits; new submissions remain blocked until capacity returns, and delivery workers remain bounded. Preserve this exception when tuning backlog alerts.

## Release and rollback gates

Build all applications from one source revision and record per-JAR SHA256, frontend file hashes, runtime image IDs, application image IDs and applied Flyway versions in a release manifest. Reconstruct/uploaded artifacts must match the tested hashes. Keep the existing compose, current binaries and a SQL checkpoint before cutover. Exclude secrets from the manifest.

Stop old media workers before V6 migration; mixed versions would bypass the new admission guard. V6 removes the implicit one-job-per-owner generated unique index and installs explicit bounded admission. Do not restore an old media binary into an unrestricted active queue. Before traffic resumes, rollback can re-establish its original generated index only if there is at most one pending job per owner. Auth V12 is additive and leaves existing financial audit/late callbacks intact. Never restore a whole SQL dump over newly accepted transactions as routine rollback.

Validate real MySQL migrations and concurrent admission separately from H2 tests. The local MySQL test creates random disposable schemas and refuses remote URLs. With an isolated MySQL instance:

```sh
export MEDIA_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13316/mysql?allowPublicKeyRetrieval=true&useSSL=false'
export MEDIA_TEST_MYSQL_USERNAME=root
# Supply MEDIA_TEST_MYSQL_PASSWORD privately.
./mvnw -pl agent-media-service -am -Dtest=ImageJobMySqlHardeningTest -Dsurefire.failIfNoSpecifiedTests=false test
```

These tests exercise races/replays, not load capacity. No paid provider is contacted. Full verification runs `./mvnw verify`; CI also checks operational backup/metric privacy and tamper guards.

## Management and recovery

The production SSH transition must verify a second key connection before disabling password authentication and rotating the recovery password. Keep the old connection open through reload and reconnect. Deliver access/recovery secrets only through a protected local file. NFS may be disabled only after verifying mounts/exports/dependencies. Nacos stays on loopback host ports, enables client/admin/console authentication together, and uses a service role scoped to the application namespace/group; rotate administrator credentials separately. Stage client credentials and JVM limits with the server change.

Nginx adds nosniff, referrer policy and a short initial HSTS lifetime. CSP starts in report-only mode to allow resource auditing without breaking the existing frontend. Key login, staged configuration validation and post-reload checks are release requirements, not claims of network high availability.

See [monitoring](../ops/monitoring/README.md) and [encrypted backups](../ops/backup/README.md). An OSS upload is not a verified backup until ACL/readback/retention and a restore drill pass. Missing IAM permissions must leave timers disabled. The archive key belongs in a separately protected recovery location, never the bucket or source repository. Initial online snapshots have documented component consistency; coordinated recovery requires quiescing writers and verifying MySQL/document/vector relationships.

## Remaining measured improvements

Keep prompt gallery disabled until its actual embedding space/configuration and import are verified. Existing parser/vector fixtures do not prove semantic recall. OCR remains an explicit unsupported boundary for scanned PDFs. Establish representative Chinese text, table/chart and fuzzy-gallery queries, record recall@K/citation correctness and evaluate embedding/model changes against pinned identities. Visual memory's bounded MySQL vector scan and cloud history's initial full snapshot hydration are follow-on scale limits; replacing them requires owner filtering, version/CAS handling and recovery tests. Single-host high availability and real payment acceptance require separate infrastructure/provider validation.
