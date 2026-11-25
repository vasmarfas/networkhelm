"""Works out what a Release run builds and where it publishes, from the form of the run.

Reads the inputs of the form from FORM_* variables, the manifest of the earlier run SOURCE_RUN whose builds are published
again (SOURCE_MANIFEST) and, for an empty version, appVersionName of build.gradle.kts. HAS_* tell which secrets are set. Writes
the outputs of the step, manifest.json for the artifacts of the run and the plan into the summary, and stops the run with
an error before anything is built when the form asks for what cannot work.

Usage: FORM_VERSION=1.4.2 FORM_GITHUB=publish ... python tools/release/plan.py
"""
import json
import os
import re
import sys
import uuid
from pathlib import Path

NOTES = ["notes_en", "notes_ru", "notes_github", "notes_apple_en", "notes_apple_ru", "notes_rustore"]
GITHUB_FILES = [
    "release-android",
    "release-windows-x64",
    "release-linux-x64",
    "release-linux-appimage-x64",
    "release-macos-arm64",
    "release-macos-x64",
    "release-gateway-linux",
    "release-gateway-windows",
    "release-web",
]
errors = []


def form(name):
    return os.environ.get(f"FORM_{name.upper()}", "").strip()


def flag(name):
    return form(name) == "true"


def has(name):
    return os.environ.get(f"HAS_{name.upper()}", "") == "true"


def app_version():
    found = re.search(r'appVersionName: String = .*\?: "([^"]+)"', Path("build.gradle.kts").read_text(encoding="utf-8"))
    return found.group(1) if found else ""


# the same packing as in build.gradle.kts: 1.4.12-rc2 is 1041202, the final 1.4.12 is 1041299
def derived_code(version):
    numbers = (version.split("-")[0].split(".") + ["0", "0", "0"])[:3]
    major, minor, patch = (int(n) if n.isdigit() else 0 for n in numbers)
    stage = 99
    if "-" in version:
        tail = re.search(r"(\d+)$", version)
        stage = int(tail.group(1)) if tail else 0
    return str(((major * 100 + minor) * 100 + patch) * 100 + stage)


def length(notes):
    return len(notes.replace("\\n", "\n"))


def main():
    source = {}
    if os.environ.get("SOURCE_RUN"):
        manifest = Path(os.environ["SOURCE_MANIFEST"])
        if not manifest.is_file():
            print(f"::error::run {os.environ['SOURCE_RUN']} has no manifest: it is not a Release run of 1.4.2 or later, or its artifacts have expired")
            sys.exit(1)
        source = json.loads(manifest.read_text(encoding="utf-8"))
    reuse = bool(source)

    version = form("version") or source.get("version") or app_version()
    if reuse and form("version") and form("version") != source["version"]:
        errors.append(f"the builds of run {source['builds_run']} are of version {source['version']}, not {form('version')}")
    if not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?", version):
        errors.append(f"version '{version}' is not like 1.4.2 or 1.5.0-rc1")
    code = form("version_code") or source.get("code") or derived_code(version)
    if reuse and form("version_code") and form("version_code") != source["code"]:
        errors.append(f"the builds of run {source['builds_run']} carry build number {source['code']}, not {form('version_code')}")
    if not code.isdigit():
        errors.append(f"build number '{code}' is not a number")
    if not reuse and form("version") and form("version") != app_version():
        print(f"::warning::build.gradle.kts says {app_version()}, the run builds {version}")
    prerelease = "-" in version
    commit = source.get("commit") or os.environ["GITHUB_SHA"]

    notes = {name: form(name) or source.get(name, "") for name in NOTES}
    notes_en, notes_ru = notes["notes_en"], notes["notes_ru"]
    apple_en = notes["notes_apple_en"] or notes_en
    apple_ru = notes["notes_apple_ru"] or notes_ru
    rustore_notes = notes["notes_rustore"] or notes_ru or notes_en

    github = form("github") or "publish"
    google_play, rustore, app_store, mac_app_store = (flag(n) for n in ("google_play", "rustore", "app_store", "mac_app_store"))
    stores = google_play or rustore or app_store or mac_app_store
    pages = flag("pages")
    if pages and prerelease:
        print(f"::notice::{version} is a prerelease, GitHub Pages keeps the page of the last version")
        pages = False
    if pages and os.environ.get("GITHUB_REF") != "refs/heads/master":
        print("::notice::GitHub Pages takes only a run from master, the page is not published")
        pages = False
    # the image, the announcement and the scan go with a new GitHub release
    docker = flag("docker") and github != "skip"
    announce = flag("announce") and github == "publish"
    virustotal = flag("virustotal") and github == "publish"
    if announce and not has("telegram"):
        print("::warning::TG_BOT_TOKEN is not set, there is no announcement")
        announce = False
    if virustotal and not has("virustotal"):
        print("::warning::VT_API_KEY is not set, the files are not scanned")
        virustotal = False

    # a dry run only builds, the run that publishes its builds asks for the notes
    dry_run = flag("dry_run")
    if (github != "skip" or stores) and not (notes_en and notes_ru) and not dry_run:
        errors.append("notes_en and notes_ru are needed for the GitHub release and the stores")
    if google_play:
        for name, text in (("notes_en", notes_en), ("notes_ru", notes_ru)):
            if length(text) > 500:
                errors.append(f"{name} is {length(text)} characters, Google Play takes up to 500")
    if app_store or mac_app_store:
        for name, text in (("Apple notes in English", apple_en), ("Apple notes in Russian", apple_ru)):
            if length(text) > 4000:
                errors.append(f"{name} are {length(text)} characters, the App Store takes up to 4000")

    files = github != "skip"
    build = {
        "android": files or google_play or rustore,
        "windows": files,
        "linux": files,
        "macos": files or mac_app_store,
        "mac_app_store": mac_app_store,
        "ios": app_store,
        "gateway": files or docker,
    }
    build["desktop"] = build["windows"] or build["linux"] or build["macos"]
    # one build of the web app gives the bundle of the desktop app and the gateway, the page and its archive
    build["web"] = build["desktop"] or build["gateway"] or files or pages

    artifacts = list(GITHUB_FILES) if files else []
    artifacts += ["release-android"] if google_play or rustore else []
    artifacts += ["store-ios"] if app_store else []
    artifacts += ["store-mac-app-store"] if mac_app_store else []
    artifacts += ["release-gateway-linux"] if docker else []
    artifacts += ["pages-site"] if pages else []
    artifacts = list(dict.fromkeys(artifacts))
    if not artifacts:
        errors.append("nothing is chosen: neither the GitHub release, nor a store, nor GitHub Pages")

    if not dry_run:
        if (google_play or rustore) and not reuse and not has("keystore"):
            errors.append("KEYSTORE_BASE64 is not set, an unsigned build does not go to Google Play and RuStore")
        if google_play and not has("play"):
            errors.append("PLAY_SERVICE_ACCOUNT_JSON is not set, Google Play cannot take the build")
        if rustore and not has("rustore"):
            errors.append("RUSTORE_KEY_ID or RUSTORE_PRIVATE_KEY is not set, RuStore cannot take the build")
        if (app_store or mac_app_store) and not has("asc"):
            errors.append("ASC_KEY_ID, ASC_ISSUER_ID or ASC_KEY_P8 is not set, App Store Connect cannot take the builds")
        if app_store and not reuse and not has("ios"):
            errors.append("the iOS signing secrets are not all set, the IPA cannot be built")
        if mac_app_store and not reuse and not has("mac_app_store"):
            errors.append("the Mac App Store secrets are not all set, the package cannot be built")
    elif reuse:
        print("::notice::a dry run of builds made earlier only checks that their files are still there")

    if errors:
        for error in errors:
            print(f"::error::{error}")
        sys.exit(1)

    if reuse:
        build = {name: False for name in build}
    targets = {
        "github": "skip" if dry_run else github,
        "google_play": google_play and not dry_run,
        "rustore": rustore and not dry_run,
        "app_store": app_store and not dry_run,
        "mac_app_store": mac_app_store and not dry_run,
        "pages": pages and not dry_run,
        "docker": docker and not dry_run,
        "announce": announce and not dry_run,
        "virustotal": virustotal and not dry_run,
    }
    review = form("review") or "publish after approval"
    outputs = {
        "version": version,
        "code": code,
        "commit": commit,
        "prerelease": prerelease,
        "builds_run": source.get("builds_run", ""),
        **{f"build_{name}": value for name, value in build.items()},
        **targets,
        "play_track": form("play_track") or "production",
        "submit": review != "upload only",
        "release_after_review": review == "publish after approval",
        "artifacts": " ".join(artifacts),
        "notes_en": notes_en,
        "notes_ru": notes_ru,
        "notes_github": notes["notes_github"],
        "notes_apple_en": apple_en,
        "notes_apple_ru": apple_ru,
        "notes_rustore": rustore_notes,
    }
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as file:
        for name, value in outputs.items():
            value = str(value).lower() if isinstance(value, bool) else str(value)
            mark = uuid.uuid4().hex
            file.write(f"{name}<<{mark}\n{value}\n{mark}\n")

    Path("manifest.json").write_text(json.dumps({
        "version": version,
        "code": code,
        "commit": commit,
        "builds_run": source.get("builds_run") or os.environ["GITHUB_RUN_ID"],
        **notes,
    }, ensure_ascii=False, indent=1), encoding="utf-8")

    names = {"android": "Android", "windows": "Windows", "linux": "Linux", "macos": "macOS", "mac_app_store": "Mac App Store package",
             "ios": "iOS", "gateway": "gateway", "web": "web"}
    built = ", ".join(label for name, label in names.items() if build[name])
    if reuse:
        builds = f"from run {source['builds_run']}"
    else:
        builds = (built or "nothing") + (", nothing is published" if dry_run else "")

    def store(on, details=""):
        return ("yes" + (f", {details}" if details else "")) if on else "no"

    rows = [
        ("Version", f"{version} ({code}), commit {commit[:7]}" + (", prerelease" if prerelease else "")),
        ("Builds", builds),
        ("GitHub release", targets["github"]),
        ("Google Play", store(targets["google_play"], f"{outputs['play_track']}, {review}")),
        ("RuStore", store(targets["rustore"], review)),
        ("App Store", store(targets["app_store"], review)),
        ("Mac App Store", store(targets["mac_app_store"], review)),
        ("GitHub Pages", store(targets["pages"])),
        ("Gateway image", store(targets["docker"])),
        ("Announcement", store(targets["announce"])),
        ("VirusTotal", store(targets["virustotal"])),
    ]
    summary = "## Plan\n\n| | |\n|---|---|\n" + "".join(f"| {name} | {value} |\n" for name, value in rows)
    print(summary)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
        file.write(summary + "\n")


if __name__ == "__main__":
    main()
