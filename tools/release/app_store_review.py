"""Prepares the App Store versions for the builds a release uploaded, and sends them to review if asked.

For each platform in PLATFORMS (IOS, MAC_OS) it finds or creates the version VERSION_NAME, writes the notes of every
localization, waits until the build BUILD_NUMBER is processed, attaches it and, with SUBMIT=true, puts the version into
a review submission. The key comes from ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_P8, as PEM text or base64.

Usage: BUNDLE_ID=... PLATFORMS="IOS MAC_OS" VERSION_NAME=... BUILD_NUMBER=... RELEASE_TYPE=MANUAL SUBMIT=false
       NOTES_EN=... NOTES_RU=... python tools/release/app_store_review.py
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com/v1"
EDITABLE = {
    "PREPARE_FOR_SUBMISSION",
    "READY_FOR_REVIEW",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}
BUILD_WAIT_MINUTES = 90

KEY = os.environ["ASC_KEY_P8"]
if "BEGIN" not in KEY:
    KEY = base64.b64decode("".join(KEY.split())).decode()
NOTES_EN = os.environ["NOTES_EN"] or os.environ["NOTES_RU"]
NOTES_RU = os.environ["NOTES_RU"] or os.environ["NOTES_EN"]


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path}: HTTP {status} {body}")
        self.status = status


def call(method, path, data=None):
    now = int(time.time())
    token = jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        KEY,
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"]},
    )
    request = urllib.request.Request(
        API + path,
        method=method,
        data=None if data is None else json.dumps({"data": data}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        raise ApiError(method, path, error.code, error.read().decode()) from None
    return json.loads(body)["data"] if body else None


def get(path, params):
    return call("GET", f"{path}?{urllib.parse.urlencode(params)}")


def state_of(version):
    attributes = version["attributes"]
    return attributes.get("appVersionState") or attributes.get("appStoreState")


def prepare_version(app_id, platform, version_string):
    versions = get(f"/apps/{app_id}/appStoreVersions", {"filter[platform]": platform, "limit": 200})
    same = next((v for v in versions if v["attributes"]["versionString"] == version_string), None)
    if same and state_of(same) not in EDITABLE:
        return same, False
    attributes = {"versionString": version_string, "releaseType": os.environ["RELEASE_TYPE"]}
    # App Store Connect keeps one editable version per platform, a draft made by hand is renamed instead
    current = same or next((v for v in versions if state_of(v) in EDITABLE), None)
    if current:
        call("PATCH", f"/appStoreVersions/{current['id']}", {
            "type": "appStoreVersions",
            "id": current["id"],
            "attributes": attributes,
        })
        return current, True
    created = call("POST", "/appStoreVersions", {
        "type": "appStoreVersions",
        "attributes": {"platform": platform, **attributes},
        "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
    })
    return created, True


def set_whats_new(version_id):
    for localization in get(f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", {"limit": 50}):
        locale = localization["attributes"]["locale"]
        call("PATCH", f"/appStoreVersionLocalizations/{localization['id']}", {
            "type": "appStoreVersionLocalizations",
            "id": localization["id"],
            "attributes": {"whatsNew": NOTES_RU if locale.startswith("ru") else NOTES_EN},
        })


def wait_for_build(app_id, platform, version_string, build_number):
    params = {
        "filter[app]": app_id,
        "filter[version]": build_number,
        "filter[preReleaseVersion.version]": version_string,
        "filter[preReleaseVersion.platform]": platform,
    }
    deadline = time.monotonic() + BUILD_WAIT_MINUTES * 60
    while True:
        builds = get("/builds", params)
        state = builds[0]["attributes"]["processingState"] if builds else "NOT_LISTED_YET"
        if state == "VALID":
            return builds[0]["id"]
        if state in ("FAILED", "INVALID"):
            sys.exit(f"{platform} build {build_number} is {state}, see the email from App Store Connect")
        if time.monotonic() > deadline:
            sys.exit(f"{platform} build {build_number} is still {state} after {BUILD_WAIT_MINUTES} minutes, rerun this job later")
        print(f"{platform} build {build_number}: {state}", flush=True)
        time.sleep(60)


def submit(app_id, platform, version_id):
    pending = get("/reviewSubmissions", {
        "filter[app]": app_id,
        "filter[platform]": platform,
        "filter[state]": "READY_FOR_REVIEW",
    })
    submission = pending[0] if pending else call("POST", "/reviewSubmissions", {
        "type": "reviewSubmissions",
        "attributes": {"platform": platform},
        "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
    })
    try:
        call("POST", "/reviewSubmissionItems", {
            "type": "reviewSubmissionItems",
            "relationships": {
                "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission["id"]}},
                "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}},
            },
        })
    except ApiError as error:
        # a run that stopped before the last call leaves the version already inside the pending submission
        if not pending or error.status != 409:
            raise
        print(f"{platform}: {error}", flush=True)
    call("PATCH", f"/reviewSubmissions/{submission['id']}", {
        "type": "reviewSubmissions",
        "id": submission["id"],
        "attributes": {"submitted": True},
    })


def main():
    bundle_id = os.environ["BUNDLE_ID"]
    app_id = next(a["id"] for a in get("/apps", {"filter[bundleId]": bundle_id}) if a["attributes"]["bundleId"] == bundle_id)
    version_string = os.environ["VERSION_NAME"].split("-")[0]
    build_number = os.environ["BUILD_NUMBER"]
    summary = []
    for platform in os.environ["PLATFORMS"].split():
        version, editable = prepare_version(app_id, platform, version_string)
        if not editable:
            summary.append(f"{platform} {version_string}: already {state_of(version)}, left as is.")
            continue
        set_whats_new(version["id"])
        build_id = wait_for_build(app_id, platform, version_string, build_number)
        call("PATCH", f"/appStoreVersions/{version['id']}/relationships/build", {"type": "builds", "id": build_id})
        if os.environ["SUBMIT"] == "true":
            submit(app_id, platform, version["id"])
            summary.append(f"{platform} {version_string} ({build_number}): sent to review.")
        else:
            summary.append(f"{platform} {version_string} ({build_number}): ready, not sent to review.")
    print("\n".join(summary))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
        file.write("".join(f"App Store Connect, {line}\n" for line in summary))


if __name__ == "__main__":
    try:
        main()
    except ApiError as error:
        sys.exit(str(error))
