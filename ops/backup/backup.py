#!/usr/bin/env python3
"""Python 3.6+ backup runner. Secrets never appear in command arguments/logs."""
import argparse
import base64
import datetime
import email.utils
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import ssl
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

MAGIC = b"AGBK0001"
CHUNK = 1024 * 1024
OBJECT = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{32}\.abk$")


def protected(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError("secret file must be root-owned regular file mode 0600 or stricter")
    return path


def load_config(path):
    if os.name != "posix" or os.geteuid() != 0:
        raise ValueError("runner requires Linux root")
    with protected(path).open() as handle:
        cfg = json.load(handle)
    checked_prefix(cfg["oss"]["prefix"])
    protected(cfg["key_file"])
    protected(cfg["mysql"]["defaults_file"])
    if not cfg.get("milvus", {}).get("collections") or not cfg.get("vector_command"):
        raise ValueError("logical vector snapshot configuration is mandatory")
    if not cfg.get("document_paths") or not cfg.get("config_paths"):
        raise ValueError("document and runtime configuration paths are mandatory")
    if not cfg.get("model_metadata"):
        raise ValueError("exact model/version/fingerprint metadata is mandatory")
    if bool(cfg.get("pause_command")) != bool(cfg.get("resume_command")):
        raise ValueError("pause/resume hooks must be configured together")
    return cfg


def checked_prefix(prefix):
    if not re.fullmatch(r"private/[A-Za-z0-9][A-Za-z0-9_/-]{1,150}/", prefix):
        raise ValueError("use a dedicated private/<backup-name>/ prefix")
    if any(part in ("", ".", "..") for part in prefix[:-1].split("/")):
        raise ValueError("unsafe prefix")
    return prefix


def read_key(path):
    key = protected(path).read_bytes()
    if len(key) != 32:
        raise ValueError("key file must contain exactly 32 random bytes")
    return key


def derived(key, purpose):
    return hmac.new(key, b"agent-backup-v1/" + purpose, hashlib.sha256).digest()


def envelope_header():
    return {"format": 1, "cipher": "AES-256-CBC", "kdf": "PBKDF2-HMAC-SHA256",
            "iterations": 200000, "mac": "HMAC-SHA256", "created": int(time.time())}


def openssl_transform(source, dest, key, executable, decrypting=False):
    args = [executable, "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-md", "sha256",
            "-pass", "stdin", "-in", str(source), "-out", str(dest)]
    args += ["-d"] if decrypting else ["-salt"]
    # Pass a domain-separated secret through a pipe, never argv or environment.
    password = base64.b64encode(derived(key, b"encryption")) + b"\n"
    process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    process.communicate(password)
    if process.returncode:
        if Path(dest).exists():
            Path(dest).unlink()
        raise RuntimeError("encryption command failed")


def pack_envelope(cipher, dest, key, header):
    encoded = json.dumps(header, sort_keys=True, separators=(",", ":")).encode("ascii")
    mac = hmac.new(derived(key, b"authentication"), digestmod=hashlib.sha256)
    with Path(dest).open("xb") as out, Path(cipher).open("rb") as stream:
        first = MAGIC + struct.pack(">I", len(encoded)) + encoded
        out.write(first)
        mac.update(first)
        while True:
            block = stream.read(CHUNK)
            if not block:
                break
            out.write(block)
            mac.update(block)
        out.write(mac.digest())


def authenticate(path, key):
    size = Path(path).stat().st_size
    if size < 80:
        raise ValueError("invalid encrypted archive")
    mac = hmac.new(derived(key, b"authentication"), digestmod=hashlib.sha256)
    with Path(path).open("rb") as stream:
        remaining = size - 32
        while remaining:
            block = stream.read(min(CHUNK, remaining))
            if not block:
                raise ValueError("truncated archive")
            mac.update(block)
            remaining -= len(block)
        if not hmac.compare_digest(mac.digest(), stream.read(32)):
            raise ValueError("archive authentication failed")
        stream.seek(0)
        if stream.read(8) != MAGIC:
            raise ValueError("unknown archive format")
        length = struct.unpack(">I", stream.read(4))[0]
        if length > 4096 or length < 1 or length + 44 >= size:
            raise ValueError("invalid archive header")
        header = json.loads(stream.read(length).decode("ascii"))
        expected = envelope_header()
        if any(header.get(k) != v for k, v in expected.items() if k != "created"):
            raise ValueError("unsupported encryption parameters")
    return 12 + length, size - 32 - 12 - length


def encrypt(source, dest, key, executable):
    with tempfile.TemporaryDirectory(prefix="cipher-", dir=str(Path(dest).parent)) as directory:
        cipher = Path(directory) / "cipher"
        openssl_transform(source, cipher, key, executable)
        pack_envelope(cipher, dest, key, envelope_header())
    authenticate(dest, key)


def decrypt(source, dest, key, executable):
    # Entire metadata + ciphertext MAC is checked before OpenSSL sees any ciphertext.
    offset, length = authenticate(source, key)
    with tempfile.TemporaryDirectory(prefix="cipher-", dir=str(Path(dest).parent)) as directory:
        cipher = Path(directory) / "cipher"
        with Path(source).open("rb") as stream, cipher.open("xb") as out:
            stream.seek(offset)
            while length:
                block = stream.read(min(CHUNK, length))
                out.write(block)
                length -= len(block)
        openssl_transform(cipher, dest, key, executable, True)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    with temporary.open("x") as out:
        json.dump(value, out, sort_keys=True, indent=2)
        out.write("\n")
        out.flush()
        os.fsync(out.fileno())
    os.replace(str(temporary), str(path))


def file_inventory(directory):
    return {p.relative_to(directory).as_posix(): sha256(p) for p in Path(directory).rglob("*")
            if p.is_file() and p != Path(directory) / "manifest.json"}


def write_manifest(directory, metadata):
    write_json(Path(directory) / "manifest.json", {"format": 1, "metadata": metadata, "files": file_inventory(directory)})


def verify_manifest(directory):
    with (Path(directory) / "manifest.json").open() as handle:
        manifest = json.load(handle)
    if manifest.get("format") != 1 or manifest.get("files") != file_inventory(directory):
        raise ValueError("snapshot file inventory mismatch")
    return manifest


def safe_extract(path, dest):
    dest = Path(dest)
    dest.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tarfile.open(str(path), "r:gz") as archive:
        seen = set()
        for member in archive.getmembers():
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or "\\" in member.name or member.name in seen:
                raise ValueError("unsafe archive member")
            if not member.isdir() and not member.isfile():
                raise ValueError("archive links/devices are forbidden")
            seen.add(member.name)
        archive.extractall(str(dest))


def checked_copy(source, dest, forbidden):
    source, dest = Path(source), Path(dest)
    resolved = source.resolve()
    if any(resolved == item or resolved in item.parents for item in forbidden):
        raise ValueError("snapshot would include key or backup credentials")
    info = source.lstat()
    if stat.S_ISLNK(info.st_mode):
        raise ValueError("snapshot paths must not contain symlinks")
    if stat.S_ISDIR(info.st_mode):
        dest.mkdir(mode=0o700)
        for child in source.iterdir():
            checked_copy(child, dest / child.name, forbidden)
    elif stat.S_ISREG(info.st_mode):
        shutil.copyfile(str(source), str(dest))
        os.chmod(str(dest), 0o600)
    else:
        raise ValueError("snapshot paths must contain only regular files/directories")


def run(args, stdin=None, stdout=None):
    # Suppress dependency error bodies: they may contain credentials or row values.
    subprocess.check_call([str(a) for a in args], stdin=stdin, stdout=stdout or subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, timeout=3600)


def mysql_dump_args(cfg):
    mysql = cfg["mysql"]
    databases = mysql["databases"]
    if not databases or any(not re.fullmatch(r"[A-Za-z0-9_]+", db) for db in databases):
        raise ValueError("explicit safe MySQL database allowlist required")
    return [mysql.get("dump_bin", "/usr/bin/mysqldump"), "--defaults-extra-file=" + mysql["defaults_file"],
            "--single-transaction", "--quick", "--routines", "--events", "--triggers", "--hex-blob",
            "--set-gtid-purged=OFF", "--no-tablespaces", "--databases"] + databases


class OSSStore:
    """OSS REST signature v1 over TLS, streaming PUT/GET, private object ACL."""
    def __init__(self, config):
        self.cfg = config
        self.prefix = checked_prefix(config["prefix"])
        endpoint = urllib.parse.urlsplit(config["endpoint"])
        if endpoint.scheme != "https" or endpoint.path not in ("", "/") or endpoint.query or endpoint.username:
            raise ValueError("HTTPS OSS endpoint required")
        if not re.fullmatch(r"oss-[a-z0-9-]+\.aliyuncs\.com", endpoint.netloc):
            raise ValueError("use an official regional OSS endpoint without bucket prefix")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", config["bucket"]):
            raise ValueError("invalid OSS bucket")
        self.host = config["bucket"] + "." + endpoint.netloc

    def allowed(self, key):
        return key.startswith(self.prefix) and OBJECT.fullmatch(key[len(self.prefix):]) is not None

    def request(self, method, key="", query=None, source=None, dest=None, extra=None):
        if key and not self.allowed(key):
            raise ValueError("OSS object outside dedicated backup namespace")
        query = query or {}
        headers = {"Date": email.utils.formatdate(usegmt=True)}
        headers.update(extra or {})
        if self.cfg.get("security_token"):
            headers["x-oss-security-token"] = self.cfg["security_token"]
        if source:
            headers["Content-Length"] = str(Path(source).stat().st_size)
            headers["Content-Type"] = "application/octet-stream"
        canonical_oss = "".join(k.lower() + ":" + headers[k].strip() + "\n"
                                for k in sorted(headers, key=str.lower) if k.lower().startswith("x-oss-"))
        resource = "/" + self.cfg["bucket"] + "/" + key
        # Only OSS-defined subresources enter the signature. Listing filters do not.
        if "acl" in query:
            resource += "?acl"
        sign = method + "\n\n" + headers.get("Content-Type", "") + "\n" + headers["Date"] + "\n" + canonical_oss + resource
        signature = base64.b64encode(hmac.new(self.cfg["access_key_secret"].encode(), sign.encode(), hashlib.sha1).digest()).decode()
        headers["Authorization"] = "OSS " + self.cfg["access_key_id"] + ":" + signature
        target = "/" + urllib.parse.quote(key, safe="/")
        if query:
            target += "?" + urllib.parse.urlencode(query)
        conn = http.client.HTTPSConnection(self.host, timeout=120, context=ssl.create_default_context())
        try:
            conn.putrequest(method, target)
            for k, v in headers.items():
                conn.putheader(k, v)
            conn.endheaders()
            if source:
                with Path(source).open("rb") as stream:
                    for block in iter(lambda: stream.read(CHUNK), b""):
                        conn.send(block)
            response = conn.getresponse()
            if response.status not in (200, 204):
                raise RuntimeError("OSS request rejected (HTTP {})".format(response.status))
            if dest:
                with Path(dest).open("xb") as out:
                    for block in iter(lambda: response.read(CHUNK), b""):
                        out.write(block)
                return b""
            data = response.read(16 * CHUNK + 1)
            if len(data) > 16 * CHUNK:
                raise ValueError("OSS metadata response exceeds bound")
            return data
        finally:
            conn.close()

    def upload(self, key, path):
        if Path(path).stat().st_size >= 5 * 1024 ** 3:
            raise ValueError("archive exceeds single PUT limit; configure multipart helper before use")
        self.request("PUT", key, source=path, extra={"x-oss-object-acl": "private"})

    def validate(self, key, path, key_material):
        acl = ET.fromstring(self.request("GET", key, {"acl": ""}))
        if [e.text for e in acl.iter() if e.tag.split("}")[-1] == "Grant"] != ["private"]:
            raise ValueError("backup object ACL must be private")
        with tempfile.TemporaryDirectory(prefix="readback-", dir=str(Path(path).parent)) as directory:
            downloaded = Path(directory) / "archive.abk"
            self.request("GET", key, dest=downloaded)
            authenticate(downloaded, key_material)
            if sha256(downloaded) != sha256(path):
                raise ValueError("offsite readback mismatch")

    def list_keys(self):
        marker, seen = "", set()
        while True:
            root = ET.fromstring(self.request("GET", query={"prefix": self.prefix, "marker": marker, "max-keys": "1000"}))
            text = lambda name: next((e.text or "" for e in root if e.tag.split("}")[-1] == name), "")
            for content in root:
                if content.tag.split("}")[-1] == "Contents":
                    for item in content:
                        if item.tag.split("}")[-1] == "Key" and self.allowed(item.text or ""):
                            yield item.text
            if text("IsTruncated") != "true":
                break
            marker = text("NextMarker")
            if not marker or marker in seen:
                raise ValueError("invalid OSS pagination")
            seen.add(marker)

    def delete(self, key):
        if not self.allowed(key):
            raise ValueError("refusing foreign object deletion")
        self.request("DELETE", key)


def retention_candidates(keys, prefix, current, cutoff, limit):
    checked_prefix(prefix)
    if not 1 <= limit <= 100:
        raise ValueError("retention delete limit must be 1..100")
    selected = []
    for key in keys:
        if not key.startswith(prefix) or not OBJECT.fullmatch(key[len(prefix):]) or key == current:
            continue
        try:
            timestamp = datetime.datetime.strptime(key[len(prefix):len(prefix) + 16], "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc).timestamp()
        except ValueError:
            continue
        if timestamp < cutoff:
            selected.append(key)
    return sorted(set(selected))[:limit]


def upload_and_retain(store, prefix, name, path, key, days, limit):
    if not 7 <= int(days) <= 3650:
        raise ValueError("retention must be 7..3650 days")
    current = prefix + name
    store.upload(current, path)
    store.validate(current, path, key)
    selected = retention_candidates(store.list_keys(), prefix, current, time.time() - int(days) * 86400, limit)
    for old in selected:
        store.delete(old)
    return current, len(selected)


def snapshot(cfg, cfg_path, stage):
    directory = stage / "snapshot"
    directory.mkdir(mode=0o700)
    started = int(time.time())
    paused = False
    try:
        if cfg.get("pause_command"):
            # Arm cleanup before invoking pause in case the hook stops only some writers.
            paused = True
            run(cfg["pause_command"])
        with (directory / "mysql.sql").open("xb") as out:
            run(mysql_dump_args(cfg), stdout=out)
        forbidden = [Path(cfg["key_file"]).resolve(), Path(cfg_path).resolve(), stage.resolve()]
        for group in ("document_paths", "config_paths"):
            target = directory / group
            target.mkdir(mode=0o700)
            for entry in cfg[group]:
                alias = entry["name"]
                if not re.fullmatch(r"[A-Za-z0-9_-]+", alias):
                    raise ValueError("snapshot alias must be a safe component")
                checked_copy(entry["path"], target / alias, forbidden)
        vector_dir = directory / "milvus"
        vector_dir.mkdir(mode=0o700)
        run(cfg["vector_command"] + ["export", str(cfg_path), str(vector_dir)])
        if not (vector_dir / "snapshot.json").is_file():
            raise ValueError("logical vector snapshot missing; backup aborted")
        write_manifest(directory, {"started": started, "ended": int(time.time()),
                                  "quiescence_hook": paused, "model": cfg["model_metadata"],
                                  "consistency": "component snapshots; no distributed atomicity",
                                  "mysql_databases": cfg["mysql"]["databases"]})
        verify_manifest(directory)
        archive = stage / "snapshot.tar.gz"
        with tarfile.open(str(archive), "w:gz") as out:
            for child in sorted(directory.iterdir()):
                out.add(str(child), arcname=child.name, recursive=True)
        return archive
    finally:
        if paused:
            run(cfg["resume_command"])


def verify_mysql(cfg, directory, stage):
    """Restore SQL into a fresh, network-isolated local Docker container only."""
    image = cfg["restore_mysql_image"]
    name = "backup_verify_" + uuid.uuid4().hex
    password = base64.b64encode(os.urandom(32)).decode()
    env = stage / "mysql-verify.env"
    env.write_text("MYSQL_ROOT_PASSWORD=" + password + "\n", encoding="ascii")
    option = stage / "mysql-verify.cnf"
    option.write_text("[client]\nuser=root\npassword=" + password + "\n", encoding="ascii")
    run(["docker", "image", "inspect", image])  # Never pull during a verification run.
    try:
        run(["docker", "run", "-d", "--pull=never", "--name", name, "--label", "agent.backup.verify=" + name,
             "--network", "none", "--memory", cfg.get("restore_mysql_memory", "1g"),
             "--env-file", str(env), "-v", str(option) + ":/run/verify.cnf:ro", image, "--event-scheduler=OFF"])
        deadline = time.time() + 120
        while True:
            try:
                run(["docker", "exec", name, "mysqladmin", "--defaults-extra-file=/run/verify.cnf", "ping", "--silent"])
                break
            except subprocess.CalledProcessError:
                if time.time() >= deadline:
                    raise RuntimeError("isolated MySQL startup timeout")
                time.sleep(2)
        with (directory / "mysql.sql").open("rb") as stream:
            run(["docker", "exec", "-i", name, "mysql", "--defaults-extra-file=/run/verify.cnf"], stdin=stream)
        databases = cfg["mysql"]["databases"]
        run(["docker", "exec", name, "mysqlcheck", "--defaults-extra-file=/run/verify.cnf", "--check", "--databases"] + databases)
        # SQL import + CHECK TABLE verifies parse/storage integrity, not application semantics.
    finally:
        found = subprocess.run(["docker", "inspect", "--format", '{{index .Config.Labels "agent.backup.verify"}}', name],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if found.returncode == 0 and found.stdout.decode().strip() == name:
            run(["docker", "rm", "-f", "-v", name])


def report(cfg, operation, ok, started, previous, detail=None):
    status = {"operation": operation, "ok": bool(ok), "finished": int(time.time()),
              "duration_seconds": round(time.time() - started, 3),
              "last_success": int(time.time()) if ok and operation == "backup" else previous.get("last_success", 0),
              "last_restore_success": int(time.time()) if ok and operation == "restore-check" else previous.get("last_restore_success", 0)}
    status["lastSuccessTimestamp"] = status["last_success"]
    status["status"] = "SUCCESS" if ok else "FAILED"
    if detail:
        status.update(detail)
    destination = Path(cfg["status_dir"])
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    write_json(destination / "status.json", status)
    text = "agent_backup_last_success_timestamp_seconds {}\nagent_backup_last_restore_success_timestamp_seconds {}\nagent_backup_last_run_ok {}\nagent_backup_last_duration_seconds {}\n".format(
        status["last_success"], status["last_restore_success"], int(ok), status["duration_seconds"])
    temporary = destination / ("backup.prom.tmp-" + uuid.uuid4().hex)
    temporary.write_text(text, encoding="ascii")
    os.chmod(str(temporary), 0o644)
    os.replace(str(temporary), str(destination / "backup.prom"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["backup", "restore-check"])
    parser.add_argument("--config", required=True)
    parser.add_argument("--archive", help="local encrypted archive for restore-check")
    parser.add_argument("--object", help="exact dedicated-prefix OSS object for restore-check")
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(signum, frame):
        raise RuntimeError("backup interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    cfg = load_config(args.config)
    key = read_key(cfg["key_file"])
    if args.operation == "restore-check" and bool(args.archive) == bool(args.object):
        parser.error("restore-check requires exactly one of --archive or --object")
    import fcntl
    lock_path = Path(cfg["lock_file"])
    lock_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("backup skipped: another backup/verification holds the lock", file=sys.stderr)
            return 75
        started, previous = time.time(), {}
        old_status = Path(cfg["status_dir"]) / "status.json"
        if old_status.is_file():
            with old_status.open() as handle:
                previous = json.load(handle)
        scratch = Path(cfg["scratch_dir"])
        scratch.mkdir(mode=0o700, parents=True, exist_ok=True)
        if scratch.stat().st_uid != 0 or scratch.stat().st_mode & 0o077:
            raise ValueError("scratch directory must be root-owned mode 0700")
        # Previous SIGKILL/power loss can leave private staging. The same lock is held,
        # and only our exact, root-owned, non-link children are eligible for cleanup.
        for stale in scratch.iterdir():
            if not stale.name.startswith("agent-backup-"):
                continue
            info = stale.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
                raise ValueError("unsafe stale staging entry")
            if stale.resolve().parent != scratch.resolve():
                raise ValueError("stale staging escaped scratch directory")
            shutil.rmtree(str(stale))
        try:
            detail = {}
            with tempfile.TemporaryDirectory(prefix="agent-backup-", dir=str(scratch)) as temporary:
                stage = Path(temporary)
                if args.operation == "backup":
                    archive = snapshot(cfg, args.config, stage)
                    encrypted = stage / "archive.abk"
                    encrypt(archive, encrypted, key, cfg.get("openssl", "/usr/bin/openssl"))
                    name = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex + ".abk"
                    unused, deleted = upload_and_retain(OSSStore(cfg["oss"]), cfg["oss"]["prefix"], name, encrypted,
                                                        key, cfg.get("retention_days", 30), cfg.get("max_delete_per_run", 20))
                    pointer_dir = Path(cfg["status_dir"])
                    pointer_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
                    pointer = pointer_dir / ("recovery-object.tmp-" + uuid.uuid4().hex)
                    pointer.write_text(unused + "\n", encoding="ascii")
                    os.replace(str(pointer), str(pointer_dir / "recovery-object.txt"))
                    detail = {"archive_bytes": encrypted.stat().st_size, "retention_deleted": deleted}
                else:
                    encrypted = Path(args.archive) if args.archive else stage / "download.abk"
                    if args.object:
                        OSSStore(cfg["oss"]).request("GET", args.object, dest=encrypted)
                    archive = stage / "restored.tar.gz"
                    decrypt(encrypted, archive, key, cfg.get("openssl", "/usr/bin/openssl"))
                    restored = stage / "restored"
                    safe_extract(archive, restored)
                    manifest = verify_manifest(restored)
                    if manifest["metadata"]["mysql_databases"] != cfg["mysql"]["databases"]:
                        raise ValueError("restore database allowlist mismatch")
                    verify_mysql(cfg, restored, stage)
                    run(cfg["vector_command"] + ["verify", str(args.config), str(restored / "milvus")])
            report(cfg, args.operation, True, started, previous, detail)
            print(args.operation + " completed")
            return 0
        except Exception as exc:
            report(cfg, args.operation, False, started, previous, {"error_class": type(exc).__name__})
            print(args.operation + " failed; see sanitized status.json", file=sys.stderr)
            return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("backup configuration/startup failed ({})".format(type(error).__name__), file=sys.stderr)
        sys.exit(1)
