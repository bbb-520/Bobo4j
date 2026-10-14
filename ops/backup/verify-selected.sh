#!/bin/sh
set -eu
umask 077
object=$(cat /opt/agentdemo/backup/recovery-object.txt)
exec /usr/bin/python3 /opt/agentdemo/backup/backup.py restore-check \
  --config /etc/agentdemo-backup/config.json --object "$object"
