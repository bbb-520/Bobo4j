#!/bin/sh
# Compile against the exact dependencies in the deployed RAG fat JAR. No Maven/install.
set -eu
umask 077
test "$#" -eq 1 || { echo "usage: build-helper.sh /absolute/path/rag.jar" >&2; exit 2; }
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
rag=$1
mkdir -p "$base/build/lib" "$base/build/classes"
python3 - "$rag" "$base/build/lib" <<'PY'
import pathlib, re, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    libs = [entry for entry in jar.infolist() if re.fullmatch(r'BOOT-INF/lib/[^/]+\.jar', entry.filename)]
    if not any(entry.filename.endswith('/milvus-sdk-java-2.6.4.jar') for entry in libs):
        raise ValueError('exact Milvus SDK 2.6.4 required')
    for entry in libs:
        target = pathlib.Path(sys.argv[2]) / pathlib.PurePosixPath(entry.filename).name
        target.write_bytes(jar.read(entry))
PY
javac -cp "$base/build/lib/*" -d "$base/build/classes" "$base/java/BackupMilvus.java" "$base/java/BackupMilvusSelfTest.java"
jar --create --file "$base/build/ops-helper.jar" -C "$base/build/classes" .
java -cp "$base/build/ops-helper.jar:$base/build/lib/*" agent.backup.BackupMilvusSelfTest
printf '%s\n' "Built $base/build/ops-helper.jar"
