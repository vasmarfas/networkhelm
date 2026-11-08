"""Prints the table of downloads that opens the body of a GitHub release.

Reads the folder of the release files and links every file that is there into the cell of its system and architecture; a
platform whose build failed leaves its cell empty. The names are the ones the deploy workflows give their files.

Usage: python tools/release/downloads.py FOLDER VERSION OWNER/REPOSITORY [DOCKER_IMAGE]
"""
import sys
from pathlib import Path

folder = Path(sys.argv[1])
version = sys.argv[2]
repository = sys.argv[3]
image = sys.argv[4] if len(sys.argv) > 4 else ""
owner, project = repository.split("/")
download = f"https://github.com/{repository}/releases/download/v{version}"
unproven = False


def links(*files):
    found = [f"[{label}]({download}/{name})" for label, name in files if (folder / name).is_file()]
    return " &nbsp; ".join(found)


def cell(*files, proven=True):
    global unproven
    text = links(*files)
    if text and not proven:
        unproven = True
        return text + " \\*"
    return text


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in rows if any(row[1:])]
    return "\n".join(lines)


name = f"NetworkHelm-{version}"
windows = cell(("EXE", f"{name}-windows-setup.exe"), ("Portable", f"{name}-windows-portable.exe"), ("MSI", f"{name}-windows.msi"))
windows_arm = cell(("EXE", f"{name}-windows-arm64-setup.exe"), ("Portable", f"{name}-windows-arm64-portable.exe"), proven=False)
windows_legacy = cell(("EXE", f"{name}-windows-legacy-setup.exe"), ("Portable", f"{name}-windows-legacy-portable.exe"), proven=False)
linux = cell(("DEB", f"{name}-linux-amd64.deb"), ("RPM", f"{name}-linux-x86_64.rpm"), ("AppImage", f"{name}-linux-x86_64.AppImage"))
linux_arm = cell(("DEB", f"{name}-linux-arm64.deb"), ("RPM", f"{name}-linux-aarch64.rpm"), ("AppImage", f"{name}-linux-aarch64.AppImage"), proven=False)
android = cell(("APK", f"{name}.apk"))
ios = f"[Xcode project](https://github.com/{repository}/tree/v{version}/app/iosApp)"
page = f"[Go](https://{owner}.github.io/{project}/)" if "-" not in version else cell(("ZIP", f"NetworkHelm-web-{version}.zip"))

gateway = f"NetworkHelm-gateway-{version}"
blocks = [
    table(
        ["Architecture", "Windows", "Linux", "macOS", "Android", "iOS", "Web"],
        [
            ["x86-64 (64-bit)", windows, linux, cell(("DMG", f"{name}-macos-x64.dmg")), android, "", page],
            ["AArch64 (ARM64)", windows_arm, linux_arm, cell(("DMG", f"{name}-macos-arm64.dmg")), android, ios, ""],
            ["ARMv7 (32-bit)", "", "", "", android, "", ""],
            ["x86-64, Windows 7 and 8", windows_legacy, "", "", "", "", ""],
        ],
    ),
    table(
        ["Gateway for the browser version", "Windows", "Linux", "Any system, Java 17 or newer"],
        [
            ["x86-64 (64-bit)", cell(("ZIP", f"{gateway}-windows-x64.zip")), cell(("TAR.GZ", f"{gateway}-linux-x64.tar.gz")), cell(("JAR", f"{gateway}.jar"))],
            ["AArch64 (ARM64)", cell(("ZIP", f"{gateway}-windows-arm64.zip"), proven=False), cell(("TAR.GZ", f"{gateway}-linux-arm64.tar.gz"), proven=False), ""],
        ],
    ),
]
if image:
    blocks.append(f"Docker image of the gateway for amd64 and arm64: `{image.lower()}:{version}`")
if unproven:
    blocks.append("\\* Experimental: built on its own runner and not yet run on that hardware. / "
                  "Экспериментально: собрано на отдельном раннере, на этом железе пока не запускалось.")
# the place for the verdicts of virustotal.py, which runs once the release is public
blocks.append("<!-- virustotal -->\n<!-- /virustotal -->")
sys.stdout.buffer.write(("\n\n".join(blocks) + "\n").encode("utf-8"))
