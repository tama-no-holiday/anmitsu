"""Maintainer-only fetch of pinned Mozc-UT dictionaries (explicit network action; the app never downloads).

Standard library only. Run it with an isolated interpreter and explicit paths, e.g.

    <python> -I scripts/fetch-ut-dictionaries.py check-latest --lock third_party/mozc-ut/sources.lock.json
    <python> -I scripts/fetch-ut-dictionaries.py fetch --lock third_party/mozc-ut/sources.lock.json \
        --source sudachidict --cache .astra/local/oss-ut
    <python> -I scripts/fetch-ut-dictionaries.py verify --dir .astra/local/oss-ut/sudachidict/<revision>

fetch writes into a new directory <cache>/<name>/<revision>/ (never overwrites; a partial download is left under a
.partial-* name and can be deleted): the bz2 archive, its decompressed text, the notice files and source.properties
for `ossCli ut-prepare`. A revision other than the lock's, or a lock entry without archiveSha256, is fetched as an
*unpinned candidate*: the hash is recorded and printed, and must be reviewed and written into the lock before adoption.
The lock file is never modified by this script.
"""

import argparse
import bz2
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request

HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
FILE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")
REPO = re.compile(r"^https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)$")

MAX_ARCHIVE_BYTES = 300 * 1024 * 1024
MAX_TEXT_BYTES = 512 * 1024 * 1024  # same bound as UtDictionaryImport.Policy.maxSourceBytes
MAX_NOTICE_BYTES = 1024 * 1024
TIMEOUT_SECONDS = 60
CHUNK = 1 << 16


def fail(message):
    print("error: " + message, file=sys.stderr)
    sys.exit(2)


def load_lock(path):
    with open(path, "r", encoding="utf-8") as f:
        lock = json.load(f)
    if lock.get("format") != 1:
        fail("unsupported lock format")
    sources = {}
    for s in lock["sources"]:
        if not NAME.match(s["name"]) or not REPO.match(s["repository"]):
            fail("invalid lock entry name/repository")
        if s["revision"] is not None and not HEX40.match(s["revision"]):
            fail("invalid pinned revision for " + s["name"])
        if s["archiveSha256"] is not None and not HEX64.match(s["archiveSha256"]):
            fail("invalid archiveSha256 for " + s["name"])
        if not FILE.match(s["archive"]) or not s["archive"].endswith(".bz2"):
            fail("invalid archive name for " + s["name"])
        if s["pos"] not in ("general-noun", "person-name"):
            fail("invalid pos for " + s["name"])
        if not s["notices"] or not all(FILE.match(n) for n in s["notices"]):
            fail("invalid notices for " + s["name"])
        sources[s["name"]] = s
    return sources


def open_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": "astra-ime-dictionary-maintainer"})
    return urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS)


def latest_revision(repository):
    owner, repo = REPO.match(repository).groups()
    with open_url("https://api.github.com/repos/%s/%s/commits/HEAD" % (owner, repo)) as r:
        data = json.loads(r.read(MAX_NOTICE_BYTES).decode("utf-8"))
    sha = data.get("sha", "")
    if not HEX40.match(sha):
        fail("unexpected API answer for " + repository)
    return sha, data.get("commit", {}).get("committer", {}).get("date", "?")


def download(url, target, limit):
    digest = hashlib.sha256()
    size = 0
    with open_url(url) as r, open(target, "xb") as out:
        while True:
            chunk = r.read(CHUNK)
            if not chunk:
                break
            size += len(chunk)
            if size > limit:
                fail("%s is larger than %d bytes" % (url, limit))
            digest.update(chunk)
            out.write(chunk)
    return digest.hexdigest(), size


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decompress(archive, target):
    digest = hashlib.sha256()
    size = 0
    with bz2.open(archive, "rb") as src, open(target, "xb") as out:
        while True:
            chunk = src.read(CHUNK)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_TEXT_BYTES:
                fail("decompressed text is larger than %d bytes" % MAX_TEXT_BYTES)
            digest.update(chunk)
            out.write(chunk)
    return digest.hexdigest(), size


def cmd_check_latest(args):
    for name, s in load_lock(args.lock).items():
        sha, date = latest_revision(s["repository"])
        pinned = s["revision"] or "(not pinned)"
        state = "up to date with the pin" if sha == s["revision"] else "NEWER UPSTREAM REVISION AVAILABLE"
        print("%s: pinned=%s upstream=%s (committed %s) %s" % (name, pinned, sha, date, state))
    print("Upstream data may itself be stale; a new revision does not mean current vocabulary.")


def cmd_fetch(args):
    sources = load_lock(args.lock)
    if args.source not in sources:
        fail("unknown source " + args.source)
    s = sources[args.source]
    revision = args.revision or s["revision"]
    if revision is None:
        fail("%s has no pinned revision; pass --revision <40-hex commit> (see check-latest)" % args.source)
    if not HEX40.match(revision):
        fail("--revision must be a 40-hex commit, not a branch or tag")
    pinned = revision == s["revision"] and s["archiveSha256"] is not None
    owner, repo = REPO.match(s["repository"]).groups()
    final = os.path.join(args.cache, s["name"], revision)
    if os.path.exists(final):
        fail("%s already exists; use verify, or delete it explicitly to refetch" % final)
    os.makedirs(os.path.dirname(final), exist_ok=True)
    work = tempfile.mkdtemp(prefix=".partial-%s-" % revision[:12], dir=os.path.dirname(final))

    def raw(path):
        return "https://raw.githubusercontent.com/%s/%s/%s/%s" % (owner, repo, revision, path)

    archive = os.path.join(work, s["archive"])
    archive_sha, archive_bytes = download(raw(s["archive"]), archive, MAX_ARCHIVE_BYTES)
    if pinned and archive_sha != s["archiveSha256"]:
        fail("archive sha256 %s does not match the lock (%s); left in %s" % (archive_sha, s["archiveSha256"], work))
    text_name = s["archive"][: -len(".bz2")]
    text_sha, text_bytes = decompress(archive, os.path.join(work, text_name))
    notices = {}
    for n in s["notices"]:
        notices[n] = download(raw(n), os.path.join(work, n), MAX_NOTICE_BYTES)[0]
    props = [
        ("name", s["name"]),
        ("repository", s["repository"]),
        ("revision", revision),
        ("archive", s["archive"]),
        ("archiveSha256", archive_sha),
        ("archiveBytes", str(archive_bytes)),
        ("text", text_name),
        ("textSha256", text_sha),
        ("textBytes", str(text_bytes)),
        ("license", s["license"]),
        ("pos", s["pos"]),
        ("notices", ",".join(s["notices"])),
        ("pinnedInLock", "true" if pinned else "false"),
    ] + [("notice.%s.sha256" % n, notices[n]) for n in s["notices"]]
    if pinned and s.get("rows"):
        # ut-prepare fails if the line count differs from the pinned one.
        props.append(("expectedRows", str(s["rows"])))
    with open(os.path.join(work, "source.properties"), "x", encoding="utf-8", newline="\n") as f:
        for key, value in props:
            f.write("%s=%s\n" % (key, value))
    os.rename(work, final)
    print("fetched %s at %s into %s" % (s["name"], revision, final))
    print("archiveSha256=%s archiveBytes=%d textSha256=%s textBytes=%d" % (archive_sha, archive_bytes, text_sha, text_bytes))
    if not pinned:
        print("UNPINNED CANDIDATE: review the notices and data, then record revision and archiveSha256 in the lock.")


def cmd_verify(args):
    props = {}
    with open(os.path.join(args.dir, "source.properties"), "r", encoding="utf-8") as f:
        for line in f.read().splitlines():
            if line and not line.startswith("#"):
                key, _, value = line.partition("=")
                props[key] = value
    checks = [(props["archive"], props["archiveSha256"]), (props["text"], props["textSha256"])]
    checks += [(n, props["notice.%s.sha256" % n]) for n in props["notices"].split(",")]
    for name, expected in checks:
        if not FILE.match(name):
            fail("invalid file name in source.properties")
        if sha256_file(os.path.join(args.dir, name)) != expected:
            fail("%s does not match source.properties" % name)
    print("verified %d files of %s at %s" % (len(checks), props["name"], props["revision"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("check-latest", help="print the upstream HEAD commit of every locked source (read-only)")
    p.add_argument("--lock", required=True)
    p.set_defaults(func=cmd_check_latest)
    p = sub.add_parser("fetch", help="download one source at a pinned commit into a new cache directory")
    p.add_argument("--lock", required=True)
    p.add_argument("--source", required=True)
    p.add_argument("--cache", required=True)
    p.add_argument("--revision", help="explicit 40-hex commit (an unpinned candidate unless it equals the lock)")
    p.set_defaults(func=cmd_fetch)
    p = sub.add_parser("verify", help="re-hash a fetched directory against its source.properties (offline)")
    p.add_argument("--dir", required=True)
    p.set_defaults(func=cmd_verify)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
