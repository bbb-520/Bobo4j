param([string]$Container = 'agent-mysql')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testDatabase = 'codex_billing_verify_' + [guid]::NewGuid().ToString('N')
if ($testDatabase -notmatch '^codex_billing_verify_[a-f0-9]{32}$') { throw 'Invalid isolated database name' }
$mysqlCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -u root --batch --skip-column-names'
$migrationFiles = @(
 'agent-auth-service/src/main/resources/db/migration/V1__auth_baseline.sql',
 'agent-auth-service/src/main/resources/db/migration/V2__qq_email_identity.sql',
 'agent-auth-service/src/main/resources/db/migration/V3__billing.sql',
 'agent-auth-service/src/main/resources/db/migration/V4__existing_wallets.sql',
 'agent-media-service/src/main/resources/db/migration/V1__media_baseline.sql',
 'agent-media-service/src/main/resources/db/migration/V2__media_lease_idempotency.sql',
 'agent-media-service/src/main/resources/db/migration/V3__media_generation_admission.sql',
 'agent-media-service/src/main/resources/db/migration/V4__media_provider_receipt.sql',
 'agent-content-service/src/main/resources/db/migration/V1__content_baseline.sql',
 'agent-content-service/src/main/resources/db/migration/V2__publish_prompt_snapshot.sql',
 'agent-content-service/src/main/resources/db/migration/V3__zine_generation_receipts.sql'
)
$sql = "CREATE DATABASE $testDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE $testDatabase;`n"
foreach ($file in $migrationFiles) {
 $sql += (Get-Content -LiteralPath (Join-Path $workspace $file) -Raw) + "`n"
 if ($file.EndsWith('V1__auth_baseline.sql')) {
  $sql += "INSERT INTO app_user(id,username,password_hash,created_at) VALUES (7,'migration_fixture','fixture',NOW());`n"
 }
}
$sql += @'
SELECT CONCAT('uuid_preserved=',public_user_id) FROM app_user WHERE id=7;
SELECT CONCAT('existing_trial=',free_images) FROM billing_wallet;
SELECT CONCAT('tables=',COUNT(*)) FROM information_schema.tables WHERE table_schema=DATABASE();
'@
try {
 $output = $sql | docker exec -i $Container sh -c $mysqlCommand
 if ($LASTEXITCODE -ne 0) { throw 'MySQL migration execution failed' }
 # Java UUID.nameUUIDFromBytes("bobo:user:7"), verified independently using the JDK.
 if ($output -notcontains 'uuid_preserved=ad26db5c-7e4d-39bd-87b3-e1991585dca2') {
  throw 'Public user UUID changed during migration'
 } else { $output | Write-Output }
 if ($output -notcontains 'existing_trial=0') { throw 'Existing users received registration promotion' }
 Write-Output 'MySQL migrations applied successfully to an isolated test database.'
} finally {
 "DROP DATABASE IF EXISTS $testDatabase;" | docker exec -i $Container sh -c $mysqlCommand | Out-Null
 if ($LASTEXITCODE -ne 0) { Write-Warning "Remove isolated test database $testDatabase manually." }
}
