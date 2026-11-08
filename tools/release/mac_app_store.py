"""Signs the Mac App Store image of the desktop app and packs it into an installer for App Store Connect.

Compose names the signing identity of a store build only as 3rd Party Mac Developer Application, so the image built with
-PmacAppStore stays unsigned and this script signs it inside out: the native libraries packed in the jars, every Mach-O
file of the app and of the runtime, the runtime bundle with its own profile, then the app with the sandbox entitlements.
An identity of - signs ad hoc without profiles and without the entitlements a profile has to allow, so the sandboxed
build can be started on this Mac; Apple does not start a store signed build outside the store.

Usage: python3 tools/release/mac_app_store.py APP IDENTITY [INSTALLER_IDENTITY PROFILE RUNTIME_PROFILE PKG]
"""
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

macos = Path(__file__).resolve().parents[2] / "app" / "desktopApp" / "macos"
restricted = ("com.apple.application-identifier", "com.apple.developer.team-identifier")
machO = {b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"}


def entitlements(name, work, adhoc):
    with open(macos / name, "rb") as f:
        values = plistlib.load(f)
    if adhoc:
        for key in restricted:
            values.pop(key, None)
    path = work / name
    with open(path, "wb") as f:
        plistlib.dump(values, f)
    return path


def sign(target, identity, entitlement=None):
    command = ["codesign", "--force", "--options", "runtime", "--sign", identity]
    if identity != "-":
        command.append("--timestamp")
    if entitlement:
        command += ["--entitlements", str(entitlement)]
    subprocess.run(command + [str(target)], check=True)


def is_mach_o(path):
    with open(path, "rb") as f:
        head = f.read(4)
    # a Java class file starts with the same CAFEBABE as a universal binary
    return head in machO and not path.name.endswith(".class")


def sign_jar(jar, identity, work):
    with zipfile.ZipFile(jar) as source:
        native = [i for i in source.infolist() if i.filename.endswith((".dylib", ".jnilib"))]
        if not native:
            return
        signed = {}
        for info in native:
            file = work / "jar" / info.filename
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(source.read(info))
            sign(file, identity)
            signed[info.filename] = file.read_bytes()
        rebuilt = jar.with_suffix(".signed")
        with zipfile.ZipFile(rebuilt, "w") as target:
            for info in source.infolist():
                target.writestr(info, signed.get(info.filename) or source.read(info.filename))
    os.replace(rebuilt, jar)
    shutil.rmtree(work / "jar")


def main(app, identity, installer="", profile="", runtime_profile="", pkg=""):
    app = Path(app)
    adhoc = identity == "-"
    work = Path(tempfile.mkdtemp())
    runtime = app / "Contents" / "runtime"
    helper = entitlements("app-store-helper-entitlements.plist", work, adhoc)
    # jpackage gives the runtime the bundle id of the app, and App Store Connect refuses two bundles with one id
    runtime_info = runtime / "Contents" / "Info.plist"
    with open(runtime_info, "rb") as f:
        info = plistlib.load(f)
    if not info["CFBundleIdentifier"].startswith("com.oracle.java."):
        info["CFBundleIdentifier"] = "com.oracle.java." + info["CFBundleIdentifier"]
        with open(runtime_info, "wb") as f:
            plistlib.dump(info, f)
    if not adhoc:
        shutil.copy(profile, app / "Contents" / "embedded.provisionprofile")
        shutil.copy(runtime_profile, runtime / "Contents" / "embedded.provisionprofile")
    for jar in sorted((app / "Contents" / "app").glob("*.jar")):
        sign_jar(jar, identity, work)
    for root in (app / "Contents" / "app", runtime / "Contents" / "Home"):
        for file in sorted(p for p in root.rglob("*") if p.is_file() and not p.is_symlink() and is_mach_o(p)):
            sign(file, identity, helper if os.access(file, os.X_OK) and file.suffix not in (".dylib", ".jnilib") else None)
    sign(runtime, identity, entitlements("app-store-runtime-entitlements.plist", work, adhoc))
    sign(app, identity, entitlements("app-store-entitlements.plist", work, adhoc))
    subprocess.run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app)], check=True)
    if pkg:
        subprocess.run(["productbuild", "--component", str(app), "/Applications", "--sign", installer, str(pkg)], check=True)
        subprocess.run(["pkgutil", "--check-signature", str(pkg)], check=True)
    shutil.rmtree(work)


if __name__ == "__main__":
    if len(sys.argv) not in (3, 7):
        sys.exit(__doc__)
    main(*sys.argv[1:])
