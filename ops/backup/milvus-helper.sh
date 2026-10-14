#!/bin/sh
set -eu
umask 077
# Root edits these non-secret deployment paths during installation.
exec /usr/bin/java -Xms32m -Xmx512m \
  -Dloader.path=/opt/agentdemo/backup/ops-helper.jar \
  -Dloader.main=agent.backup.BackupMilvus \
  -cp /opt/agentdemo/releases/current/agent-rag-service.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
