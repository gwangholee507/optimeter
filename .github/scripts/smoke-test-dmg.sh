#!/usr/bin/env bash
# CI 전용 스크립트: .dmg에 든 macOS 앱을 실제로 띄워서 smoke-test.sh와 같은 확인을 한다 (macOS에서만).
# 앱에 넣은 Java 런타임에 필요한 모듈이 빠졌거나, 앱 모드(DesktopApp)에서만 생기는 문제를 여기서 잡는다.
#
# 사용법: bash .github/scripts/smoke-test-dmg.sh <dmg 경로> [포트]
set -euo pipefail

DMG="$1"
PORT="${2:-18081}"
MOUNT="$(mktemp -d)"

hdiutil attach -nobrowse -readonly -mountpoint "$MOUNT" "$DMG" > /dev/null
trap 'hdiutil detach "$MOUNT" -force > /dev/null 2>&1 || true' EXIT

# Finder에서 연 것처럼 앱 모드(Dock 아이콘 등)는 그대로 켜고, CI에서는 브라우저만 열지 않는다
bash "$(dirname "$0")/smoke-test.sh" "$MOUNT/Optimeter.app/Contents/MacOS/Optimeter" "$PORT" \
    --optimeter.desktop.open-browser=false
