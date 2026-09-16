#!/bin/bash
set -euo pipefail
MACOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SWIFT_COMPILER="$(xcrun --find swiftc)"
SWIFT_USR_DIR="$(cd "$(dirname "$SWIFT_COMPILER")/.." && pwd)"
TEST_FLAGS=(--disable-xctest)
# Some Command Line Tools builds do not automatically discover Swift Testing's macro plugin.
for TESTING_PLUGIN in "$SWIFT_USR_DIR/lib/swift/host/plugins/testing/libTestingMacros.dylib" \
                      "$SWIFT_USR_DIR/lib/swift/host/plugins/libTestingMacros.dylib"; do
    if [[ -f "$TESTING_PLUGIN" ]]; then
        TEST_FLAGS+=(-Xswiftc -load-plugin-library -Xswiftc "$TESTING_PLUGIN")
        break
    fi
done
swift test --package-path "$MACOS_DIR" "${TEST_FLAGS[@]}" "$@"
