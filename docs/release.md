# 릴리스 방법

사용자에게 배포하는 형태는 **프론트엔드까지 들어 있는 jar 파일 하나**와, 그 jar에 Java 런타임까지 넣은
**Mac용 앱(.dmg)** 입니다. 버전 태그를 올리면 GitHub Actions([release.yml](../.github/workflows/release.yml))가
아래 파일들을 만들어 [Releases](https://github.com/gwangholee507/optimeter/releases) 페이지에 자동으로 올립니다.

| 파일 | 대상 |
|---|---|
| `optimeter-<버전>.jar` | 모든 OS. JDK 17 이상이 있으면 터미널에서 `java -jar`로 실행 |
| `optimeter-<버전>-macos-arm64.dmg` | Apple Silicon Mac. Java 없이 설치해서 실행 |
| `optimeter-<버전>-macos-x64.dmg` | Intel Mac |

## 릴리스 올리기

`main`이 배포할 상태인지(CI 통과) 확인한 뒤, `main`의 최신 커밋에 `v`로 시작하는 태그를 붙여 올립니다.

```bash
git switch main
git pull
git tag v0.1.0
git push origin v0.1.0
```

- 태그는 `v1.2.3` 또는 `v1.2.3-rc.1` 형식이어야 합니다 (다른 형식이면 워크플로가 실패합니다).
- 태그에서 `v`를 뗀 값이 버전이 됩니다. `v0.1.0` → `optimeter-0.1.0.jar`, `optimeter-0.1.0-macos-arm64.dmg`
- 버전 번호는 [유의적 버전](https://semver.org/lang/ko/)을 따릅니다: 호환되지 않는 변경은 앞자리,
  기능 추가는 가운데, 버그 수정은 끝자리를 올립니다.

## 워크플로가 하는 일

잡(job) 세 개가 순서대로 돕니다.

1. **Build jar** (Ubuntu)
   1. JDK 17 설치, clang/gcc 설치
   2. `./mvnw -Pbundle -Drevision=<버전> package` — 테스트를 통과해야 jar가 만들어짐
   3. 만든 jar를 실제로 띄워보는 스모크 테스트 ([smoke-test.sh](../.github/scripts/smoke-test.sh))
   4. jar를 워크플로 아티팩트로 업로드
2. **Build macOS app** (Apple Silicon 러너 `macos-latest`, Intel 러너 `macos-15-intel`에서 동시에)
   1. 1번에서 만든 jar로 [`packaging/macos/build-dmg.sh`](../packaging/macos/build-dmg.sh)를 실행해 .dmg 생성
   2. .dmg 안의 앱을 실제로 띄워보는 스모크 테스트 ([smoke-test-dmg.sh](../.github/scripts/smoke-test-dmg.sh)) —
      jar와 같은 확인을, 사용자가 받을 앱 그대로(앱 모드 포함, 브라우저 열기만 끔) 수행
   3. .dmg를 워크플로 아티팩트로 업로드
3. **Publish GitHub Release** (태그로 실행된 경우에만) — 1, 2번이 **모두** 성공했을 때만 GitHub Release를 만들고
   jar와 .dmg 두 개를 첨부. 릴리스 노트는 지난 릴리스 이후 머지된 PR 제목으로 자동 생성됨

## 태그를 올리기 전에 미리 확인해보기

GitHub의 **Actions → Release → Run workflow**로 수동 실행하면 1, 2번만 수행합니다. Release는 만들지 않고,
만들어진 jar와 .dmg는 실행 결과 페이지의 Artifacts에서 내려받을 수 있습니다.

## Mac용 앱(.dmg)

jar를 Mac에서 더블클릭하면 서명이 없는 파일이라 Gatekeeper가 막고, 막지 않더라도 창 없이 뒤에서만 돌아서 끌 수가 없습니다.
그래서 Mac용으로는 [jpackage](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)로 만든 앱을 따로 배포합니다.

- 앱 안에는 **같은 jar와 Java 17 런타임**이 들어 있어서 사용자는 JDK를 설치하지 않아도 됩니다. 런타임은 CPU마다 달라서
  Apple Silicon용과 Intel용을 각각의 러너에서 만듭니다 (jpackage는 다른 CPU용으로 만들 수 없음).
- 아이콘은 로고(`docs/brand/png/optimeter-mark-1024.png`)로 빌드할 때마다 만듭니다. 앱 아이콘(Finder, Dock, 오류 대화상자)뿐 아니라
  .dmg를 열었을 때 붙는 디스크 아이콘도 로고로 지정합니다 (안 하면 흰색 디스크 그림이 뜸). 디스크 아이콘을 지정하는 데
  Xcode Command Line Tools의 `SetFile`을 씁니다.
- 앱은 실행 옵션 `-Doptimeter.desktop=true`로 jar를 띄웁니다. 이때 Dock 아이콘, 브라우저 자동 열기, 빈 포트로 다시 띄우기,
  시작 실패 대화상자, 로그 파일(`~/Library/Logs/Optimeter/optimeter.log`)이 켜집니다
  ([backend.md의 `DesktopApp`](./backend.md#desktopdesktopapp--macos-앱으로-실행될-때) 참고).
- 런타임에는 필요한 Java 모듈만 넣습니다(앱 72MB, .dmg 54MB 정도. 다 넣으면 170MB가 넘음). 목록은 `build-dmg.sh`의 `--add-modules`에 있습니다.
  의존성을 추가했을 때 새 모듈이 필요하면 앱에서만 `NoClassDefFoundError`가 나는데, CI가 macOS에서 매번 앱을 만들어 띄워보므로
  PR 단계에서 드러납니다. 목록을 다시 구하는 방법은 스크립트의 주석에 있습니다.
- **Apple 개발자 서명과 공증은 하지 않습니다** (ad-hoc 서명만 함). 서명하려면 유료(연 $99) Apple Developer Program 가입이 필요합니다.
  그래서 사용자는 처음 한 번 "시스템 설정 → 개인정보 보호 및 보안 → 그래도 열기"를 거쳐야 합니다
  ([사용자 가이드](./user-guide.md#실행하기) 참고).
- jpackage는 macOS에서 첫 자리가 0인 버전(`0.3.0`)을 받지 않습니다. 그래서 임시 버전으로 앱을 만든 뒤
  `Info.plist`의 버전을 실제 값으로 고치고 다시 서명합니다. 앱의 버전에는 숫자 부분만 들어갑니다 (`0.3.0-rc.1` → `0.3.0`).

직접 만들어 보려면 macOS에서 jar를 만든 뒤 스크립트를 실행합니다 (JDK 17 이상 필요, `JAVA_HOME` 또는 PATH의 jpackage 사용).

```bash
cd backend && ./mvnw -Pbundle -DskipTests -Drevision=0.3.0 package && cd ..
packaging/macos/build-dmg.sh backend/target/optimeter-0.3.0.jar 0.3.0 dist
# → dist/optimeter-0.3.0-macos-arm64.dmg (만든 Mac의 CPU용)
```

## 잘못 올렸을 때

Release와 태그를 지우고 다시 올리면 됩니다.

```bash
gh release delete v0.1.0 --cleanup-tag
```

이미 누군가 내려받았을 수 있으므로, 같은 버전 번호를 다시 쓰기보다는 다음 버전(`v0.1.1`)으로 고쳐 올리는 편이 안전합니다.
