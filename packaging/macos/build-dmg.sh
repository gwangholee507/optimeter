#!/usr/bin/env bash
# 실행 가능한 jar로 macOS 앱(Optimeter.app)을 만들고, 그 앱을 담은 .dmg를 만든다.
#
# 사용법: packaging/macos/build-dmg.sh <jar 경로> <버전> [출력 디렉터리]
#   예)  packaging/macos/build-dmg.sh backend/target/optimeter-0.3.0.jar 0.3.0 dist
#   → dist/optimeter-0.3.0-macos-arm64.dmg  (Intel Mac에서 만들면 -macos-x64.dmg)
#
# - macOS에서만 실행된다. 앱에 Java 런타임을 넣어서 사용자는 JDK를 설치하지 않아도 된다.
#   그래서 만든 Mac의 CPU(Apple Silicon/Intel)용 앱만 나온다.
# - JDK 17 이상의 jpackage가 필요하다 (JAVA_HOME, 없으면 PATH에서 찾음). 앱에는 그 JDK의 런타임이 들어간다.
# - Apple 개발자 서명은 하지 않는다 (ad-hoc 서명만). 그래서 내려받은 사용자는 처음 한 번
#   "시스템 설정 → 개인정보 보호 및 보안 → 그래도 열기"를 거쳐야 한다 (docs/user-guide.md 참고).
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "사용법: $0 <jar 경로> <버전> [출력 디렉터리]" >&2
    exit 2
fi
if [ "$(uname -s)" != "Darwin" ]; then
    echo "macOS에서만 실행할 수 있습니다." >&2
    exit 1
fi

JAR="$1"
VERSION="$2"
OUT_DIR="${3:-.}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ICON_SOURCE="$REPO_ROOT/docs/brand/png/optimeter-mark-1024.png"

[ -f "$JAR" ] || { echo "jar 파일이 없습니다: $JAR" >&2; exit 1; }
# 버전은 Info.plist와 파일 이름에 그대로 들어가므로 형식을 확인한다 (릴리스 태그와 같은 규칙)
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
    echo "버전은 1.2.3 또는 1.2.3-rc.1 형식이어야 합니다: $VERSION" >&2
    exit 1
fi
# macOS가 앱 버전으로 받는 것은 숫자 부분뿐이다 (0.3.0-rc.1 → 0.3.0)
BUNDLE_VERSION="${VERSION%%-*}"

if [ -n "${JAVA_HOME:-}" ]; then
    JPACKAGE="$JAVA_HOME/bin/jpackage"
else
    JPACKAGE="$(command -v jpackage || true)"
fi
[ -x "$JPACKAGE" ] || { echo "jpackage를 찾지 못했습니다. JDK 17 이상을 설치하고 JAVA_HOME을 지정하세요." >&2; exit 1; }
# .dmg 디스크 아이콘을 지정할 때 쓴다 (Xcode Command Line Tools에 들어 있음)
SETFILE="$(xcrun -f SetFile 2>/dev/null || command -v SetFile || true)"
[ -n "$SETFILE" ] || { echo "SetFile을 찾지 못했습니다. xcode-select --install 로 Command Line Tools를 설치하세요." >&2; exit 1; }

case "$(uname -m)" in
    arm64) ARCH=arm64 ;;
    x86_64) ARCH=x64 ;;
    *) echo "지원하지 않는 CPU입니다: $(uname -m)" >&2; exit 1 ;;
esac

WORK="$(mktemp -d)"
# 중간에 실패해도 붙여둔 볼륨을 먼저 떼어내고 지운다
trap 'hdiutil detach -force "$WORK/mnt" > /dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
mkdir -p "$WORK/input" "$WORK/Optimeter.iconset" "$WORK/dmg" "$OUT_DIR"
cp "$JAR" "$WORK/input/optimeter.jar"

# 1) 아이콘: 브랜드 로고(1024px)를 macOS 아이콘 규격대로 둘레에 여백을 두고(824px) 크기별로 만든다
sips -z 824 824 "$ICON_SOURCE" --out "$WORK/body.png" > /dev/null
sips --padToHeightWidth 1024 1024 "$WORK/body.png" --out "$WORK/icon-1024.png" > /dev/null
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$WORK/icon-1024.png" --out "$WORK/Optimeter.iconset/icon_${size}x${size}.png" > /dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$WORK/icon-1024.png" --out "$WORK/Optimeter.iconset/icon_${size}x${size}@2x.png" > /dev/null
done
iconutil -c icns "$WORK/Optimeter.iconset" -o "$WORK/Optimeter.icns"

# 2) 앱 만들기
# --add-modules: 앱에 넣을 Java 모듈. 다 넣으면 170MB가 넘어서 필요한 것만 넣는다.
#   jar의 BOOT-INF/lib를 풀어서 `jdeps --print-module-deps --ignore-missing-deps --multi-release 17`로 구한 목록이다.
#   의존성을 추가했는데 앱에서만 NoClassDefFoundError가 나면 이 목록을 다시 구한다.
#   (릴리스 워크플로가 만든 앱을 실제로 띄워서 컴파일까지 해보므로 거기서 드러난다.)
# --app-version: jpackage는 macOS에서 첫 자리가 0인 버전(0.3.0)을 거부해서, 임시 값으로 만들고 3)에서 고친다.
# --java-options: 앱 모드(Dock 아이콘, 브라우저 자동 열기 등)를 켠다. DesktopApp 참고.
"$JPACKAGE" \
    --type app-image \
    --name Optimeter \
    --app-version 1.0.0 \
    --input "$WORK/input" \
    --main-jar optimeter.jar \
    --icon "$WORK/Optimeter.icns" \
    --mac-package-identifier io.github.gwangholee507.optimeter \
    --mac-package-name Optimeter \
    --java-options "-Doptimeter.desktop=true" \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,jdk.jfr,jdk.unsupported \
    --jlink-options "--strip-debug --strip-native-commands --no-man-pages --no-header-files --compress=2" \
    --dest "$WORK/dmg"

APP="$WORK/dmg/Optimeter.app"

# 3) 버전을 실제 값으로 고치고, 고친 내용이 서명과 어긋나지 않게 다시 ad-hoc 서명한다
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $BUNDLE_VERSION" "$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUNDLE_VERSION" "$APP/Contents/Info.plist"
sed -i '' "s/-Djpackage.app-version=1.0.0/-Djpackage.app-version=$VERSION/" "$APP/Contents/app/Optimeter.cfg"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"

# 4) .dmg: 열면 앱과 "응용 프로그램" 폴더 바로가기가 보여서 끌어다 놓아 설치한다.
#    .dmg를 열었을 때 붙는 디스크의 아이콘도 로고로 바꾼다 (없으면 흰색 디스크 그림이 뜬다).
#    디스크 아이콘은 볼륨 맨 위의 .VolumeIcon.icns + "사용자 지정 아이콘" 표시로 정해지는데, 이 표시는
#    볼륨을 붙여야만 켤 수 있어서 쓰기 가능한 이미지로 만들어 표시를 켠 뒤 압축 이미지로 바꾼다.
ln -s /Applications "$WORK/dmg/Applications"
cp "$WORK/Optimeter.icns" "$WORK/dmg/.VolumeIcon.icns"
hdiutil create -volname "Optimeter $VERSION" -srcfolder "$WORK/dmg" -fs HFS+ -format UDRW -ov "$WORK/rw.dmg" > /dev/null
mkdir "$WORK/mnt"
hdiutil attach -nobrowse -noautoopen -mountpoint "$WORK/mnt" "$WORK/rw.dmg" > /dev/null
"$SETFILE" -a C "$WORK/mnt"
# Spotlight 등이 막 붙은 볼륨을 잠깐 읽고 있으면 떼어내기가 실패할 수 있어서 한 번은 강제로 다시 시도한다
hdiutil detach "$WORK/mnt" > /dev/null 2>&1 || hdiutil detach -force "$WORK/mnt" > /dev/null

DMG="$OUT_DIR/optimeter-$VERSION-macos-$ARCH.dmg"
rm -f "$DMG"
hdiutil convert "$WORK/rw.dmg" -format UDZO -o "$DMG" > /dev/null

echo "만들었습니다: $DMG ($(du -h "$DMG" | cut -f1 | tr -d ' '))"
