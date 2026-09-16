#!/bin/bash
# Installs a pinned Android build toolchain inside this checkout. No sudo required.
# Running this script accepts Google's Android SDK licenses for the local SDK.
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool_dir="$project_dir/.tools"
download_dir="$tool_dir/downloads"

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo "This bootstrap targets Apple Silicon macOS. Use JDK 17 and Android SDK 35 on other hosts." >&2
  exit 1
fi

mkdir -p "$download_dir" "$tool_dir/android-user" "$tool_dir/gradle-cache"

download_verified() {
  local url="$1" file="$2" expected="$3" algorithm="$4"
  if [[ -f "$file" ]] && [[ "$(shasum -a "$algorithm" "$file" | awk '{print $1}')" == "$expected" ]]; then
    return
  fi
  echo "Downloading $(basename "$file")…"
  curl --fail --location --silent --show-error --retry 3 "$url" -o "$file.part"
  local actual
  actual="$(shasum -a "$algorithm" "$file.part" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $file: expected $expected, received $actual" >&2
    rm -f "$file.part"
    exit 1
  fi
  mv "$file.part" "$file"
}

# SHA-256 supplied by the official Eclipse Adoptium release API.
jdk_archive="$download_dir/temurin17.tar.gz"
if [[ ! -x "$tool_dir/jdk/Contents/Home/bin/java" ]]; then
  download_verified \
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz' \
    "$jdk_archive" \
    '196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8' 256
  mkdir -p "$tool_dir/jdk"
  tar -xzf "$jdk_archive" -C "$tool_dir/jdk" --strip-components=1
fi

export JAVA_HOME="$tool_dir/jdk/Contents/Home"
export ANDROID_HOME="$tool_dir/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$tool_dir/android-user"
export GRADLE_USER_HOME="$tool_dir/gradle-cache"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# Version 19 runs on Java 17. The checksum is Google's published SHA-1 in
# https://dl.google.com/android/repository/repository2-1.xml (cmdline-tools;19.0).
sdk_archive="$download_dir/android-commandline-tools.zip"
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  download_verified \
    'https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip' \
    "$sdk_archive" 'c3e06a1959762e89167d1cbaa988605f6f7c1d24' 1
  sdk_stage="$(mktemp -d "$tool_dir/sdk-unpack.XXXXXX")"
  unzip -q "$sdk_archive" -d "$sdk_stage"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$sdk_stage/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
  rmdir "$sdk_stage"
fi

echo "Preparing Android SDK 35 and build tools (licenses accepted locally)…"
license_log="$tool_dir/android-sdk-licenses.log"
# `yes` receives SIGPIPE when sdkmanager has consumed the answers; ignore only
# that producer's exit code, preserving sdkmanager failures via pipefail.
if ! { yes || true; } | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >"$license_log" 2>&1; then
  tail -30 "$license_log" >&2
  exit 1
fi
install_log="$tool_dir/android-sdk-install.log"
if ! "$sdkmanager" --sdk_root="$ANDROID_HOME" \
  'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'build-tools;34.0.0' >"$install_log" 2>&1; then
  tail -40 "$install_log" >&2
  exit 1
fi

echo "Android toolchain ready: $tool_dir"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "Build with: $project_dir/scripts/build-android.sh"
