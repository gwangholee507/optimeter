#!/usr/bin/env bash
# CI 전용 스크립트: 만들어진 jar를 실제로 띄워서, 서버 하나가 화면과 API를 모두 내보내는지 확인한다.
# (단위 테스트는 jar 안에 프론트엔드가 제대로 들어갔는지까지는 확인하지 못한다)
#
# 사용법: bash .github/scripts/smoke-test.sh <jar 경로> [포트]
# clang이 설치되어 있어야 한다 (실제로 한 번 컴파일해 본다).
# Windows 러너에서도 Git Bash로 실행되므로 bash와 curl만 사용한다.
set -euo pipefail

JAR="$1"
PORT="${2:-18080}"
BASE="http://127.0.0.1:${PORT}"

java -jar "$JAR" --server.port="$PORT" > smoke-server.log 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

fail() {
    echo "❌ $1"
    echo "----- server log -----"
    cat smoke-server.log
    exit 1
}

echo "서버가 뜰 때까지 기다리는 중..."
started=false
for _ in $(seq 1 60); do
    if curl -fsS "$BASE/api/compilers" -o smoke-compilers.json 2>/dev/null; then
        started=true
        break
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || fail "서버가 시작 중에 종료됨"
    sleep 1
done
[ "$started" = true ] || fail "60초 안에 서버가 뜨지 않음"

# 1) 화면: / 가 프론트엔드의 index.html이어야 하고, 거기서 참조하는 JS 파일도 받아져야 한다
curl -fsS "$BASE/" -o smoke-index.html || fail "GET / 실패"
grep -q '<div id="root">' smoke-index.html || fail "/ 응답이 프론트엔드 index.html이 아님"
SCRIPT_PATH="$(grep -o '/assets/[^"]*\.js' smoke-index.html | head -n 1)"
[ -n "$SCRIPT_PATH" ] || fail "index.html에서 JS 파일 경로를 찾지 못함"
curl -fsS "$BASE$SCRIPT_PATH" -o /dev/null || fail "GET $SCRIPT_PATH 실패"
echo "✅ 화면: / 와 $SCRIPT_PATH"

# 2) API: 컴파일러 목록
grep -q '"id":"clang"' smoke-compilers.json || fail "/api/compilers 응답에 clang이 없음"
echo "✅ API: /api/compilers"

# 3) API: 실제 컴파일 한 번
curl -fsS -X POST "$BASE/api/compile" \
    -H 'Content-Type: application/json' \
    -d '{"code":"int square(int x) { return x * x; }","compiler":"clang"}' \
    -o smoke-compile.json || fail "POST /api/compile 실패"
grep -q '"success":true' smoke-compile.json || fail "컴파일 결과가 성공이 아님: $(cat smoke-compile.json)"
echo "✅ API: /api/compile"

# 4) 낯선 이름(Host)으로 온 요청은 거부해야 한다 (DNS 리바인딩 방어, HostHeaderFilter)
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/compile" \
    -H 'Host: evil.example' \
    -H 'Content-Type: application/json' \
    -d '{"code":"int square(int x) { return x * x; }","compiler":"clang"}')"
[ "$STATUS" = "403" ] || fail "낯선 Host의 요청이 거부되지 않음 (HTTP $STATUS)"
echo "✅ 보안: 낯선 Host는 403"

echo "스모크 테스트 통과"
