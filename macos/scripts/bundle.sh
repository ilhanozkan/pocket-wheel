#!/bin/bash
set -euo pipefail
MACOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_DIR="$(cd "$MACOS_DIR/.." && pwd)"
swift build --package-path "$MACOS_DIR" -c release --arch arm64
BIN_DIR="$(swift build --package-path "$MACOS_DIR" -c release --arch arm64 --show-bin-path)"
APP_PATH="$REPO_DIR/dist/Pocket Wheel.app"
mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"
cp "$BIN_DIR/PocketWheel" "$APP_PATH/Contents/MacOS/PocketWheel"
cat > "$APP_PATH/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>PocketWheel</string>
  <key>CFBundleIdentifier</key><string>app.pocketwheel.mac</string>
  <key>CFBundleName</key><string>Pocket Wheel</string>
  <key>CFBundleDisplayName</key><string>Pocket Wheel</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.2</string>
  <key>CFBundleVersion</key><string>3</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>Receive steering, pedal, and gear controls from your Android phone on the same Wi-Fi.</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP_PATH"
printf 'Built: %s\n' "$APP_PATH"
