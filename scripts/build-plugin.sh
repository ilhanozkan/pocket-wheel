#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash scripts/fetch-scs-sdk.sh
mkdir -p dist build
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread -Iplugin tests/plugin_state_test.cpp -o build/plugin-state-test
build/plugin-state-test
clang++ -std=c++17 -arch x86_64 -mmacosx-version-min=13.0 -O2 -Wall -Wextra -Werror -pthread -Ivendor/scs-sdk/include tests/plugin_callback_test.cpp -o build/plugin-callback-test
build/plugin-callback-test
clang++ -std=c++17 -arch x86_64 -mmacosx-version-min=13.0 -O2 -Wall -Wextra -Werror -fvisibility=hidden -dynamiclib -pthread -Ivendor/scs-sdk/include plugin/pocketwheel.cpp -o dist/pocketwheel.dylib
clang++ -std=c++17 -arch x86_64 -mmacosx-version-min=13.0 -O2 -Wall -Wextra -Werror -Ivendor/scs-sdk/include tests/plugin_host.cpp -o build/plugin-host
codesign --force --sign - dist/pocketwheel.dylib
cp vendor/scs-sdk/sdk_license.txt dist/SCS-SDK-LICENSE.txt
file dist/pocketwheel.dylib
