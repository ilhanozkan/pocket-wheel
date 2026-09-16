#!/bin/bash
# Builds with checkout-local dependencies and caches; no global Java is needed.
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool_dir="$project_dir/.tools"
export JAVA_HOME="$tool_dir/jdk/Contents/Home"
export ANDROID_HOME="$tool_dir/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$tool_dir/android-user"
export GRADLE_USER_HOME="$tool_dir/gradle-cache"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" || ! -d "$ANDROID_HOME/build-tools/34.0.0" || ! -d "$ANDROID_HOME/build-tools/35.0.0" ]]; then
  "$project_dir/scripts/bootstrap-android.sh"
fi
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
cd "$project_dir/android"

if [[ $# -gt 0 ]]; then
  exec ./gradlew --no-daemon --console=plain "$@"
fi
./gradlew --no-daemon --console=plain testDebugUnitTest assembleDebug
mkdir -p "$project_dir/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$project_dir/dist/PocketWheel-android.apk"
echo "APK: $project_dir/dist/PocketWheel-android.apk"
