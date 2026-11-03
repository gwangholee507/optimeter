# 전체 흐름도

```
[브라우저]
   │  0. 페이지 로드 시 GET /api/compilers 로 컴파일러 목록(언어 포함)을 받아 언어별로 묶어서 드롭다운에 채움
   │  1. 사용자가 언어(C/C++)와 컴파일러를 고르고 코드 입력, 실행 시간 측정 여부 체크 후 "비교하기" 클릭
   │       (언어를 바꾸면 그 언어의 예제 코드가 들어가고, 고치던 코드는 언어별 초안으로 보관)
   ▼
[frontend/src/App.jsx] handleCompare()
   │  2. POST /api/compile  (화면을 내려준 바로 그 서버로 — 아래 "화면과 API는 어떻게 한 서버에서 나오나요?" 참고)
   │       { code: "...", compiler: "clang"|"gcc"|"clangxx"|"gxx", runBenchmark: true|false }
   │       (언어는 따로 보내지 않음 — 서버가 컴파일러에 설정된 language로 정함)
   ▼
[backend] CompileController.compile()
   │  3. @Valid 로 코드 검증 (비어있음/20,000자 초과 시 400 에러)
   ▼
[backend] CompilerService.compareOptimizationLevels(code, compilerId, runBenchmark)
   │  4. CompilerRegistry.resolve(compilerId) 로 요청받은 id를 실제 실행 파일 경로 + 언어(Language) +
   │     명령줄 규칙(Toolchain)으로 변환
   │       (모르는 id거나 서버에 설치 안 돼 있으면 400 에러 — 사용자 입력을 커맨드에 그대로 쓰지 않음)
   │  5. 임시 디렉터리 생성 → 언어별 파일 이름(C는 input.c, C++은 input.cpp)으로 저장
   │  6. Toolchain이 정한 레벨(clang/gcc는 O0~O3) 각각에 대해 runOneLevel() 반복 호출
   │       (컴파일러 실행은 전부 platform.ProcessRunner를 거침 — 임시 디렉터리를 작업 디렉터리로 두고 실행)
   │       (인자는 Toolchain이 만듦 — 아래는 clang/gcc 기준)
   │       - {compiler} -S -O{n} → 어셈블리(.s) 생성
   │       - {compiler} -c -O{n} → 오브젝트 파일(.o) 생성 (크기 비교용)
   │       - runBenchmark=true면 {compiler} -O{n} → 실행 파일(.exe) 링크 후 5회 실행,
   │         최솟값을 실행 시간으로 채택 (main() 없어서 링크 실패하면 조용히 건너뜀)
   │  7. 임시 디렉터리 정리
   ▼
[backend] List<OptimizationResult> 를 JSON으로 응답
   │       (레벨 id·열 제목·설명, 컴파일러 id/label, 실행 시간 포함 — 레벨 정보는 Toolchain이 정함)
   ▼
[frontend/src/App.jsx] setResults(data)
   │  8. 첫 번째 결과(clang/gcc는 -O0)를 기준(baseline)으로 삼아
   │     ("비교 기준" 버튼으로 다른 레벨을 고르면 그 레벨이 기준 — 다시 컴파일하면 첫 레벨로 돌아감)
   ▼
[frontend/src/OptimizationColumn + AssemblyView]
   │  9. diff.js 의 diffLines() 로 기준 레벨 대비 달라진 줄 계산
   ▼
[화면] 레벨별 컬럼을 나란히 렌더링 (열 제목과 ⓘ 설명은 응답에 담긴 값 그대로),
       바뀐 줄은 초록색 하이라이트, 실행 시간은 ⏱ 배지로 표시
```

## 화면과 API는 어떻게 한 서버에서 나오나요?

프론트엔드는 백엔드 주소를 따로 알지 않고 항상 **자기를 내려준 서버의 `/api`** 를 호출합니다.
누가 화면을 내려주느냐만 실행 방식에 따라 달라집니다.

```
[배포된 jar / -Pbundle 실행]                  [개발할 때]

 브라우저 ── localhost:8080 ──▶ Spring Boot     브라우저 ── localhost:5173 ──▶ Vite 개발 서버
                                ├─ /      → jar 안의 화면 파일                 ├─ /      → 소스를 바로 변환해서 응답 (수정 즉시 반영)
                                └─ /api/* → CompileController                  └─ /api/* → 127.0.0.1:8080 (Spring Boot)으로 전달
```

- **jar**: `bundle` Maven 프로필이 `frontend`를 빌드해서(`frontend/dist`) jar의 `static/` 아래에 넣습니다.
  Spring Boot는 `static/`의 파일을 자동으로 내보내므로 별도 코드가 없습니다.
- **개발**: `vite.config.js`의 `server.proxy`가 `/api` 요청만 백엔드로 넘깁니다.

두 경우 모두 브라우저 입장에서는 화면과 API가 같은 출처라서 CORS 설정이 필요 없고, 일부러 두지 않았습니다
(아래 [보안 관련 주의사항](#보안-관련-주의사항) 참고).

## 컴파일러는 어떻게 선택 가능한 목록으로 관리되나요?

`CompilerRegistry`가 `application.yml`의 `compiler.options`에 정의된 후보(예: gcc는 `gcc-16`, `gcc-15`, ...)를
서버 시작 시 한 번씩 `--version` 실행해보고 실제로 동작하는 것만 "사용 가능"으로 표시합니다.
각 컴파일러에는 언어(`language: c` / `cpp`)와 컴파일러 계열(`toolchain: clang` / `gcc`)도 함께 적어두며,
어떤 파일 이름과 인자로 컴파일할지는 이 두 값으로 정해집니다 (아래 "언어/컴파일러마다 다른 부분" 참고).
C++ 컴파일러(`clangxx` = clang++, `gxx` = g++)는 C 컴파일러와 같은 계열이고 실행 파일만 다릅니다.
화면은 `/api/compilers`의 `language`로 컴파일러를 언어별로 묶어서 "언어 → 컴파일러" 두 단계로 고르게 합니다.
후보는 PATH에 있는 실행 파일 이름일 수도, 절대 경로일 수도 있고, 특정 OS에서만 시도하는 후보
(`os-candidates`, 예: Windows의 `C:\msys64\ucrt64\bin\gcc.exe`)도 따로 둘 수 있습니다.
프론트엔드는 요청 시 `id`(예: `"gcc"`)만 넘기고, 실제 실행 파일 경로로의 변환은 항상 이 레지스트리를
거칩니다 — 사용자가 보낸 문자열이 그대로 서버 프로세스 커맨드에 들어가는 것(명령어 인젝션)을 막기 위한
화이트리스트 구조입니다. 자세한 내용은 [backend.md](./backend.md) 참고.

## OS마다 다른 부분은 어디서 처리하나요?

이 프로그램은 사용자가 자기 PC(macOS, Linux, Windows)에 설치해서 쓰는 것을 목표로 합니다.
Java와 React 코드 자체는 OS와 무관하지만, **"그 PC에 설치된 컴파일러를 찾아서 실행한다"** 는 부분은
OS마다 사정이 다릅니다. 이런 차이는 아래 세 곳에만 모아두고, 컴파일 흐름을 담당하는
`CompilerService`에는 OS별 분기를 두지 않습니다.

| OS마다 다른 것 | 처리하는 곳 | 방법 |
|---|---|---|
| 컴파일러가 설치되는 위치 | `application.yml`의 `os-candidates` + `CompilerRegistry` | PATH의 이름을 먼저 시도하고, 그 OS의 기본 설치 위치(Windows의 MSYS2, LLVM)를 이어서 시도. 그래도 못 찾으면 `OPTVIS_CLANG`/`OPTVIS_GCC`/`OPTVIS_CLANGXX`/`OPTVIS_GXX` 환경 변수로 직접 지정 |
| 프로세스 실행/종료 방식 | `platform.ProcessRunner` | 출력은 파일로 받고(파이프 버퍼가 차서 멈추는 문제 방지), 타임아웃 시 자식 프로세스까지 종료, PATH 환경 변수 이름의 대소문자 차이 흡수 |
| 지금 어떤 OS인지 판단 | `platform.HostOs` | JVM의 `os.name`으로 판단. `os-candidates`의 키(`windows`/`macos`/`linux`)를 고르는 데만 사용 |

그 밖에 OS 차이를 **분기 없이** 피해 가는 장치들:

- 컴파일러에는 `input.c`, `out-O0.s` 같은 **상대 경로**만 넘기고, 작업 디렉터리를 임시 디렉터리로 지정합니다.
  경로 구분자(`/` vs `\`)나 임시 디렉터리 위치, 경로에 섞인 공백·한글(Windows 사용자 이름 등)이 컴파일러
  인자와 에러 메시지에 나타나지 않습니다.
- 실행 파일 이름은 어디서나 `out-O{n}.exe`입니다. Windows에서는 확장자가 필요하고, 다른 OS에서는 있어도 무해합니다.
- 어셈블리와 컴파일러 출력의 줄바꿈은 `\n`으로 통일해서 응답합니다 (Windows의 컴파일러는 `\r\n`으로 씀).
- 빌드/실행 명령은 Maven Wrapper(`mvnw` / `mvnw.cmd`)와 npm 스크립트만 사용하고, 특정 셸에서만 도는 스크립트는 두지 않습니다.
- `.gitattributes`로 줄바꿈을 관리해서 Windows에서 체크아웃해도 `mvnw`가 깨지지 않습니다.

CI([ci.yml](../.github/workflows/ci.yml))는 Ubuntu, macOS, Windows 세 곳에서 같은 테스트를 돌려 이 구조가
실제로 지켜지는지 확인합니다. 컴파일러 테스트는 보통 그 컴파일러가 없으면 건너뛰지만, CI는 OS마다
`required-compilers`(환경 변수 `OPTVIS_REQUIRED_COMPILERS`)로 "그 러너에 반드시 있어야 하는 컴파일러"를 지정해서,
목록에 있는 컴파일러가 없으면 건너뛰지 않고 실패시킵니다. 러너 이미지가 바뀌어 컴파일러가 빠졌을 때
테스트가 조용히 건너뛰어져 통과하는 것을 막기 위함입니다 (특히 Windows는 CI가 유일한 확인 수단).
새 기능을 추가할 때 OS에 따라 달라지는 동작이 필요해지면
`CompilerService`에 `if (windows)`를 넣는 대신 `platform` 패키지에 넣어주세요.

> 생성되는 **어셈블리 자체**는 OS마다 다른 것이 정상입니다. 실행 파일 형식(Mach-O / ELF / PE)과
> 함수 호출 규약이 OS마다 다르기 때문이며, 이건 숨기지 않고 그대로 보여줍니다.

## 언어/컴파일러마다 다른 부분은 어디서 처리하나요?

OS 차이를 `platform` 패키지에 모아둔 것과 같은 원칙으로, 언어와 컴파일러 계열의 차이는 `toolchain` 패키지에 모아둡니다.
`CompilerService`는 임시 디렉터리, 시간 제한, 실행 시간 측정처럼 모든 컴파일러에 공통인 흐름만 맡습니다.

| 언어/컴파일러마다 다른 것 | 처리하는 곳 |
|---|---|
| 소스 파일 이름 (`input.c`, `input.cpp`)과 화면의 언어 이름 | `Language` (`application.yml`의 `language`) |
| 최적화 레벨 목록 (`O0`~`O3`)과 화면의 열 제목·설명 | `Toolchain.levels()` (`OptLevel`) |
| 어셈블리·오브젝트·실행 파일을 만드는 인자 (`-S -O2 ...`) | `Toolchain`의 `assemblyArgs()` / `objectArgs()` / `executableArgs()` |
| 설치 확인 방법과 "진짜 그 컴파일러인지" 판정 | `Toolchain.versionArgs()` / `versionMismatch()` |

어떤 `Toolchain` 구현을 쓸지는 `application.yml`의 `toolchain` 값(`ToolchainKind`)으로 정합니다.
지금은 clang/gcc와 C++의 clang++/g++ 모두 GCC식 명령줄을 쓰는 `GccStyleToolchain` 하나를 공유합니다.
새 언어나 컴파일러가 필요해지면 `CompilerService`에 `if (rust)`를 넣는 대신 이 패키지에 추가해주세요.
자세한 내용은 [backend.md](./backend.md#toolchain-패키지--언어컴파일러마다-다른-부분) 참고.

## 왜 오브젝트 파일(.o)로 크기를 재나요?

사용자가 입력하는 코드에는 보통 `main()` 함수가 없습니다 (`square`, `sum_loop` 같은 순수 함수만 있음).
`main()`이 없으면 실행 파일로 **링크**할 수 없어서(`ld: symbol(s) not found for "_main"`),
링크 단계 없이 컴파일만 하는 `-c` 옵션으로 오브젝트 파일을 만들어 크기를 비교합니다.
이 방식은 링크 오버헤드가 섞이지 않아서 오히려 "순수 코드젠 크기" 비교에는 더 적합합니다.

## 보안 관련 주의사항

`CompilerService`는 사용자가 입력한 코드를 서버 프로세스 권한으로 그대로 컴파일합니다.
별도 샌드박싱이 없으므로 **"자기 PC에서 혼자 쓰는 프로그램"** 이라는 전제가 지켜져야 안전합니다.
그 전제를 지키기 위한 장치가 두 가지 있습니다.

- **이 PC에서 오는 접속만 받습니다** (`application.yml`의 `server.address: 127.0.0.1`).
  Spring Boot의 기본값은 모든 네트워크 인터페이스에서 듣는 것이라, 그대로 두면 같은 Wi-Fi의 다른 기기가
  이 서버에 코드를 보내 실행시킬 수 있습니다. `--server.address=0.0.0.0`으로 바꿀 수는 있지만
  신뢰할 수 있는 네트워크에서만 써야 합니다.
- **다른 웹사이트가 이 서버를 호출할 수 없습니다.** CORS 허용 설정을 두지 않았기 때문에, 사용자가 이 프로그램을
  켜둔 채 다른 사이트를 방문해도 그 사이트의 스크립트가 보낸 `/api/compile` 요청은 브라우저의 사전 요청(OPTIONS)
  단계에서 거부됩니다 (`CompileControllerTest`에 이를 확인하는 테스트가 있음).

여러 사람이 접속하는 서버로 운영할 계획이 생기면 Docker 컨테이너 격리 + CPU/메모리 제한을 반드시 추가해야 합니다.

`runBenchmark=true`로 요청하면 컴파일뿐 아니라 사용자 코드로 만든 실행 파일을 **서버가 직접
실행**하므로 위험도가 한 단계 더 높습니다. 그래서:
- 기본값은 `false`(옵트인) — 프론트엔드에서 사용자가 체크박스를 명시적으로 켜야만 동작합니다.
- 컴파일러 선택은 `CompilerRegistry`의 화이트리스트를 통해서만 실제 경로로 바뀝니다 (사용자가
  임의 문자열을 커맨드로 주입할 수 없음).
- 실행마다 5초 타임아웃(`EXECUTION_TIMEOUT_SECONDS`)이 걸려 있어 무한 루프 코드가 서버를 막지 않습니다.
- 그럼에도 CPU/메모리 제한이나 네트워크 격리는 없으므로, 외부에 배포하기 전에는 반드시
  Docker 등으로 실행 환경 자체를 격리해야 합니다.
