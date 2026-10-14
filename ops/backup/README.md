# Encrypted backups and isolated restoration drills

These are deployable templates, not evidence that a server backup already exists. Fill in the real paths, databases, collection identities, and root-owned credentials, then run a backup and an isolated restore drill before enabling the timers. No credentials are included in this repository.

## Captured data and consistency

The runner dumps an explicit MySQL database allowlist using `--single-transaction --quick --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces`. It snapshots the configured document volume and Compose, Nginx, runtime environment and Nacos bootstrap configuration. Include Nacos's SQL database in the database allowlist when SQL-backed, and identify any other durable configuration/data needed by the actual installation.

The Milvus helper exports every row and partition in configured collections, all stored dense/manual sparse vectors, schema/functions/analyzers, index definitions, source server/SDK versions, and application/model identity. It defaults to rejecting a collection allowlist that omits a live collection. Batches are bounded to 1–512 rows, default 128, and use `STRONG` query iterators including growing data. Before/after counts and schema comparison catch obvious concurrent changes.

The current RAG schema's BM25 sparse vectors are internal derived data: Milvus cannot return them in `output_fields`. The helper preserves the input text and full analyzer/function/index definitions, explicitly marks these fields for rebuilding, and restores them inside Milvus without external model calls. See the [official Milvus limitation](https://milvus.io/docs/full-text-search.md). Unknown functions, dynamic schemas, partition-key schemas, and unsupported binary/half/int8 vector encodings fail closed. The current RAG/gallery field types are supported.

These checks do **not** establish a transaction spanning MySQL, documents, Milvus collections or partitions. MySQL's snapshot guarantee applies to transactional tables; concurrent DDL and nontransactional tables require coordination. Copying documents while writers run may capture different moments. For a coordinated recovery point, supply root-controlled pause/resume hooks that stop all application, ingestion, indexing and document writers, drain in-flight work and prevent DDL. Resume runs on ordinary failure too. An online backup without those hooks is a component-level recovery artifact, not a distributed atomic snapshot. Never tar running Milvus local storage/embedded etcd and call it consistent; these tools do not do that.

## Encryption, OSS and bounded retention

The whole compressed archive, including its SHA-256 file manifest, is encrypted with OpenSSL AES-256-CBC, PBKDF2-HMAC-SHA256 at 200,000 iterations and a random OpenSSL salt. A random 32-byte root key derives independent encryption and HMAC keys using domain-separated HMAC-SHA256. An encrypt-then-HMAC container authenticates the format header, parameter metadata, salt and complete ciphertext. The entire MAC is checked before decryption; tampered metadata/ciphertext or a wrong key fails without producing plaintext. OpenSSL receives the derived secret through a pipe, never argv/environment.

Only encrypted `.abk` files enter OSS. The stdlib OSS REST client uses verified HTTPS/signature v1, explicitly sets object ACL `private`, checks that ACL, downloads the whole uploaded object, validates its HMAC and compares its SHA-256 to the local encrypted archive. No signed URL is generated or printed. Archives at or above the single-PUT 5 GiB bound fail before upload; multipart support would be required for larger snapshots.

Use a dedicated `private/<backup-name>/` prefix in the existing bucket, separate from public media. Retention lists only this prefix and deletes only exact runner filenames older than the retention period. It runs only after private ACL/full readback validation, preserves the current successful upload, and deletes at most 20 old objects per run by default (maximum 100). The default retention is 30 days, accepted minimum 7. Bucket-wide ACLs/lifecycle settings are never changed. Grant a separate backup principal only prefix-scoped Put/Get/GetObjectAcl/Delete and prefix-constrained List. Ensure existing bucket policies/public grants cannot expose this prefix despite its private object ACL.

The encryption key is excluded from snapshots and must never be uploaded alongside archives. Before enabling backup, make and verify a separately protected **local** recovery copy on an operator-controlled device. Losing both copies makes recovery impossible. Keep the recovery key separate from application/media credentials.

Plaintext staging is root-only mode 0700 and removed on ordinary success/failure and SIGTERM. SIGKILL/power loss can leave staging until the next locked run removes only this runner's root-owned private staging directories. Use an encrypted local filesystem for stronger at-rest protection during that interval.

## Installation

Requires Linux root, Python 3.6+, OpenSSL 1.1.1+, Java 21, a compatible MySQL dump client, the deployed RAG fat JAR with Milvus SDK 2.6.4/Gson, and Docker for SQL restore drills. Java compilation needs a JDK. No third-party Python package, Maven installation, external embedding call or MinIO is required.

Run as root after checking real paths:

```sh
install -d -m 0700 /etc/agentdemo-backup /var/lib/agentdemo-backup/staging /run/lock/agentdemo-backup /opt/agentdemo/backup
install -m 0700 ops/backup/backup.py ops/backup/milvus-helper.sh ops/backup/verify-selected.sh /opt/agentdemo/backup/
install -m 0600 ops/backup/config.example.json /etc/agentdemo-backup/config.json
umask 077
openssl rand -out /etc/agentdemo-backup/archive.key 32
# Copy/verify archive.key on the separately protected local recovery device now.
# Edit config.json with actual paths, identities and scoped OSS credentials.
```

Create `/etc/agentdemo-backup/mysql.cnf` as root mode 0600. Passwords containing quotes/backslashes must use MySQL option-file escaping:

```ini
[client]
host=127.0.0.1
port=3306
user=YOUR_BACKUP_ACCOUNT
password="YOUR_REAL_PASSWORD"
```

The account needs the version-appropriate permissions for SELECT/SHOW VIEW/TRIGGER/EVENT and routine dumping. Test against the production MySQL version. The default expects a host client. If using a container client, provide a root-owned `mysql.dump_bin` wrapper that preserves the passed option-file semantics and stdout stream without putting credentials in argv/logs; do not assume a production container name.

Build only the helper against the exact deployed fat JAR:

```sh
sh ops/backup/build-helper.sh /actual/path/to/deployed-rag.jar
install -m 0600 ops/backup/build/ops-helper.jar /opt/agentdemo/backup/ops-helper.jar
# Edit /opt/agentdemo/backup/milvus-helper.sh with actual Java/fat-JAR paths.
python3 /opt/agentdemo/backup/backup.py backup --config /etc/agentdemo-backup/config.json
```

The build extracts dependencies under `ops/backup/build/lib`, compiles the two Java files, packages the helper JAR and runs its offline tests. It does not modify application targets or Maven's repository. The wrapper uses Spring Boot `PropertiesLauncher`/`loader.main` and the deployed SDK classpath without starting the application. Keep scripts/helper root-owned and non-writable by application accounts.

Preload the production-compatible MySQL image configured as `restore_mysql_image`; prefer a reviewed immutable digest. Drills use `--pull=never`. After the first successful backup:

```sh
sh /opt/agentdemo/backup/verify-selected.sh
install -m 0644 ops/backup/systemd/agentdemo-backup*.service ops/backup/systemd/agentdemo-backup*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now agentdemo-backup.timer agentdemo-backup-verify.timer
```

After upload/readback/retention succeed, the runner atomically updates `/opt/agentdemo/backup/recovery-object.txt`; the weekly drill uses this latest successful object. Review the schedules: daily 03:20, weekly Sunday 05:20, with jitter, in the **server's local timezone**. The service write allowlist must match real scratch/status/lock directories, which must exist before starting the service. Pause/resume hooks must work inside the service sandbox. Exit 75 means another backup/drill holds the shared exclusive `flock` lock.

## Restore verification and recovery boundaries

`restore-check --config CONFIG --object EXACT_KEY` downloads only a valid dedicated-prefix object; `--archive /path/archive.abk` uses a local encrypted copy. It authenticates before decrypting, rejects traversal/links/devices/duplicate archive members, verifies every file's manifest digest, and keeps plaintext in private temporary staging.

SQL imports into a new `backup_verify_<uuid>` Docker container with network disabled, no production data mount, event scheduling off, a random password passed via a private env file, and a separate option file mounted read-only. `mysqlcheck` checks the restored databases. Cleanup deletes only the unique container whose ownership label matches the run, including its anonymous volume. This proves SQL import/storage integrity; application semantics and cross-store relationships require recovery acceptance checks.

Milvus restore creates only fresh `backup_verify_<32 hex>` collections, reconstructs fields/functions/partitions/indexes, restores exported IDs (auto-ID disabled only in the test copy), verifies counts/schema/functions, performs primary-key sample lookups including stored vectors, and exercises rebuilt BM25 indexes. Cleanup can drop only the unique name generated by the current run. Original collection names are never insert/create/drop targets. An uncertain failed create RPC can leave an isolated verification collection: inspect it before manual cleanup. There is no broad prefix sweep. Model identity must match config; server version must match unless the operator explicitly enables `allow_server_version_mismatch` for a compatibility drill. No paid model call occurs.

For disaster recovery, first perform a drill on the recovery host, then import into newly provisioned production stores in a controlled outage. Validate ownership/version/fingerprint and document relationships before switching traffic. These tools deliberately do not overwrite existing production databases, document volumes or collections.

## Monitoring and offline verification

`/opt/agentdemo/backup/status.json` exposes `lastSuccessTimestamp` (Unix seconds, preserved across failures/drills) and `status` (`SUCCESS`/`FAILED` for the latest operation), plus operation, duration, last restore success, byte count and retention count. Failures contain only an exception class. No secret values or dependency error bodies are logged. `backup.prom` exposes backup/restore success timestamps, latest run success and duration. The status directory is root-only by default; expose/copy only `.prom` into an existing node-exporter textfile directory if necessary. Monitor stale success timestamps as well as failures. Configuration errors before the destination is trusted produce a sanitized startup error; systemd failure alerts must cover them.

Offline Python tests use temporary fixtures without OSS, database, network or model calls:

```sh
python3 -m unittest discover -s ops/backup -p 'test_*.py' -v
# Equivalent: python3 -m unittest discover -s ops/backup/tests -v
```

The helper build runs its Java tests. Coverage includes actual encryption roundtrip; metadata/ciphertext/wrong-key rejection before decryption; unsafe tar members; manifest integrity; retention namespace/failure ordering; dump flags; int64 ID preservation; dense-vector validation; BM25 handling; and mutation-name guards. These tests do **not** claim a live OSS upload, production SQL consistency or live Milvus restore succeeded. The first server backup and isolated drill are required installation checks.
