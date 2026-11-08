"""Scans the files of a folder on VirusTotal and prints a collapsed table of the verdicts.

A file whose hash VirusTotal already knows is not uploaded again. The free key allows four requests a minute and 32 MB
a file; bigger files go to an upload address of their own, up to 650 MB. The verdicts only inform: the script ends with 0
whatever the engines say, because an unsigned installer is flagged by a few of them now and then and the release is
already public by the time this runs. The table comes between the markers that downloads.py leaves in the release body.

Usage: VT_API_KEY=KEY python tools/release/virustotal.py FOLDER
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

api = os.environ.get("VT_API_URL", "https://www.virustotal.com/api/v3")
key = os.environ.get("VT_API_KEY", "")
gap = float(os.environ.get("VT_GAP", "16"))
wait = float(os.environ.get("VT_WAIT", str(25 * 60)))
direct = 32 * 1024 * 1024
last = 0.0


def call(url, data=None, headers=None):
    global last
    for _ in range(5):
        pause = last + gap - time.monotonic()
        if pause > 0:
            time.sleep(pause)
        last = time.monotonic()
        request = urllib.request.Request(url, data=data, headers={"x-apikey": key, **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=900) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429:
                raise
            time.sleep(60)
    raise RuntimeError("the quota of VirusTotal is used up")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def upload(path):
    target = f"{api}/files" if path.stat().st_size <= direct else call(f"{api}/files/upload_url")["data"]
    boundary = uuid.uuid4().hex
    head = f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n\r\n'.encode()
    body = head + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return call(target, body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})["data"]["id"]


def known(digest):
    try:
        return call(f"{api}/files/{digest}")["data"]["attributes"].get("last_analysis_stats")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def scan(path, digest):
    stats = known(digest)
    if stats:
        return stats
    analysis = upload(path)
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        state = call(f"{api}/analyses/{analysis}")["data"]["attributes"]
        if state["status"] == "completed":
            return state["stats"]
    return None


def verdict(stats):
    if not stats:
        return "no answer in time"
    total = sum(stats.get(name, 0) for name in ("malicious", "suspicious", "undetected", "harmless"))
    text = f"{stats.get('malicious', 0)}/{total}"
    return text + f", {stats['suspicious']} suspicious" if stats.get("suspicious") else text


if not key:
    print("VT_API_KEY is not set, nothing was scanned")
    sys.exit(0)

rows = ["| File | Detections | Report |", "|---|---|---|"]
flagged = 0
for path in sorted(p for p in Path(sys.argv[1]).iterdir() if p.is_file()):
    digest = sha256(path)
    try:
        stats = scan(path, digest)
        text = verdict(stats)
        flagged += bool(stats and (stats.get("malicious") or stats.get("suspicious")))
    except (urllib.error.URLError, RuntimeError, TimeoutError) as error:
        text = f"error: {error}"
    print(f"{path.name}: {text}", file=sys.stderr, flush=True)
    rows.append(f"| {path.name} | {text} | [{digest[:12]}](https://www.virustotal.com/gui/file/{digest}) |")

count = len(rows) - 2
summary = f"🛡️ VirusTotal: {flagged} of {count} files flagged / помечено файлов: {flagged} из {count}"
block = "\n".join(["<!-- virustotal -->", "<details>", f"<summary>{summary}</summary>", "", *rows, "", "</details>", "<!-- /virustotal -->"])
sys.stdout.buffer.write((block + "\n").encode("utf-8"))
