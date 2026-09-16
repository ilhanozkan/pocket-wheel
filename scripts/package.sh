#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for artifact in 'dist/Pocket Wheel.app/Contents/MacOS/PocketWheel' dist/PocketWheel-android.apk dist/pocketwheel.dylib; do
  [[ -f "$artifact" ]] || { printf 'Build missing artifact first: %s\n' "$artifact" >&2; exit 1; }
done
cp docs/SETUP.md dist/SETUP.md
cp PROTOCOL.md dist/PROTOCOL.md
cp scripts/configure-ets2.py dist/configure-ets2.py
(
  cd dist
  zip -qr PocketWheel-mac.zip 'Pocket Wheel.app' pocketwheel.dylib SCS-SDK-LICENSE.txt SETUP.md configure-ets2.py
  shasum -a 256 PocketWheel-mac.zip PocketWheel-android.apk pocketwheel.dylib > SHA256SUMS.txt
)
printf 'Ready: %s/dist/PocketWheel-mac.zip and PocketWheel-android.apk\n' "$ROOT"
