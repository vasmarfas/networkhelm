#!/bin/bash
# Uploads the builds of a folder to App Store Connect with altool, each up to three times. A build an earlier attempt already
# delivered comes back as a duplicate, and App Store Connect has it. The key comes from ASC_KEY_ID, ASC_ISSUER_ID and
# ASC_KEY_P8, as PEM text or base64.
# Usage: bash tools/release/app_store_upload.sh ios|macos FOLDER
set -euo pipefail

platform=$1
umask 077
mkdir -p ~/.appstoreconnect/private_keys
key=~/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8
if printf '%s' "$ASC_KEY_P8" | grep -q 'BEGIN'; then
    printf '%s\n' "$ASC_KEY_P8" > "$key"
else
    printf '%s' "$ASC_KEY_P8" | tr -d '\n\r ' | openssl base64 -d -A > "$key"
fi
trap 'rm -f "$key"' EXIT

for file in "$2"/*; do
    for attempt in 1 2 3; do
        if xcrun altool --upload-app -f "$file" -t "$platform" --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" 2>&1 | tee "$RUNNER_TEMP/altool.log"; then
            break
        fi
        if grep -qiE 'ITMS-4238|Redundant Binary Upload|already been uploaded' "$RUNNER_TEMP/altool.log"; then
            echo "::notice::App Store Connect already has $(basename "$file")"
            break
        fi
        # a check of App Store Connect, like 90255, refuses the same file every time
        if grep -qE '\(9[0-9]{4}\)' "$RUNNER_TEMP/altool.log"; then
            exit 1
        fi
        [ "$attempt" -lt 3 ] || exit 1
        echo "::warning::altool failed, attempt $((attempt + 1)) of 3"
        sleep 60
    done
done
