#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -f vendor/scs-sdk/include/scssdk_input.h ]]; then exit 0; fi
mkdir -p .cache vendor/scs-sdk
curl -fL --retry 3 -o .cache/scs_sdk_1_14.zip https://download.eurotrucksimulator2.com/scs_sdk_1_14.zip
printf '%s\n' 'c6c1f7376b7324994d9f9c567f3c4141fbbf305b6bf803bc4cfeef2437b2023a  .cache/scs_sdk_1_14.zip' | shasum -a 256 -c -
unzip -q -o .cache/scs_sdk_1_14.zip -d vendor/scs-sdk
