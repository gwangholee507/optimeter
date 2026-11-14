# 백엔드 (Spring Boot)

패키지 루트: `com.compareopt.optvisualizer`

## `OptVisualizerApplication`
**파일**: [`backend/src/main/java/.../OptVisualizerApplication.java`](../backend/src/main/java/com/compareopt/optvisualizer/OptVisualizerApplication.java)

Spring Boot 진입점. `main()`에서 `SpringApplication.run()`을 호출해 내장 톰캣 서버(포트 8080)를 띄웁니다.
특별한 로직은 없고, 애플리케이션을 부팅하는 역할만 합니다.

서버 설정은 [`application.yml`](../backend/src/main/resources/application.yml)에 있습니다.
- `server.address: 127.0.0.1` — 이 PC에서 오는 접속만 받습니다. 받은 코드를 컴파일/실행하는 서버라서
  같은 네트워크의 다른 기기가 접속할 수 없게 한 것입니다. 실행할 때 `--server.address=0.0.0.0`
  (또는 환경 변수 `SERVER_ADDRESS`)으로 바꿀 수 있습니다.
- `optimeter.allowed-hosts` — `localhost`나 IP 주소가 아닌 이름으로 접속해야 할 때 그 이름을 쉼표로 구분해 적습니다
  (기본값은 비어 있음, 환경 변수 `OPTVIS_ALLOWED_HOSTS`). 아래 [`HostHeaderFilter`](#hostheaderfilter) 참고.
- jar 안에 `static/` 폴더가 있으면(= `bundle` 프로필로 빌드한 경우) Spring Boot가 그 안의 화면 파일을
  `/`에서 자동으로 내보냅니다 (`/`는 `index.html`로 넘김).
- `spring.web.resources.cache` — 이름에 해시가 없는 화면 파일(`index.html`, `favicon.svg` 등)에
  `Cache-Control: no-cache`를 붙이고 `Last-Modified`는 보내지 않습니다. 해시가 붙은 `/assets/*` 파일은
  아래 [`StaticResourceConfig`](#staticresourceconfig)에서 따로 설정합니다.

---

## `CompileController`
**파일**: [`backend/src/main/java/.../controller/CompileController.java`](../backend/src/main/java/com/compareopt/optvisualizer/controller/CompileController.java)

HTTP 요청을 받는 REST 컨트롤러.

CORS 허용 설정(`@CrossOrigin`)은 일부러 두지 않습니다. 화면은 항상 이 서버와 같은 출처에서 `/api`를 호출하고
(배포된 jar에서는 이 서버가 화면도 내보내고, 개발 중에는 Vite 개발 서버가 `/api`를 넘겨줌), 이 API는 받은 코드를
컴파일/실행하므로 사용자가 방문한 다른 웹사이트의 스크립트가 호출할 수 없어야 하기 때문입니다.

### `listCompilers() : List<CompilerOptionResponse>`
- **경로**: `GET /api/compilers`
- **동작**: `CompilerRegistry.listAll()`이 반환하는 내부 `ResolvedCompiler` 리스트를, 실행 파일 경로는 뺀
  `(id, label, available, language, languageLabel)` 형태로 변환해서 응답. 순서는 `application.yml`에 적은 순서 그대로.
  프론트엔드가 페이지 로드 시 이 목록을 `language`로 묶어서 언어·컴파일러 드롭다운을 채움.

### `compile(CompileRequest request) : List<OptimizationResult>`
- **경로**: `POST /api/compile`
- **입력**: `{ "code": "...", "compiler": "clang"|"gcc"|"clangxx"|"gxx"|"rustc", "runBenchmark": true|false }`
  (`compiler` 기본값 `"clang"`, `runBenchmark` 기본값 `false`). 언어는 따로 받지 않고 컴파일러에 설정된 `language`로 정함
- **동작**: `@Valid`로 `CompileRequest`를 검증한 뒤, `CompilerService.compareOptimizationLevels()`에 세 값을 그대로 위임
- **출력**: 그 컴파일러의 toolchain이 정한 레벨(clang/gcc는 O0~O3, rustc는 0~3, s, z) 순서대로 결과를 담은 `OptimizationResult` 리스트 (JSON 배열)
- 검증 실패(코드가 비어있거나 20,000자 초과) 시 Spring이 자동으로 400 Bad Request를 반환하고,
  `GlobalExceptionHandler`가 그 예외를 가로채 `{ message: "..." }` 형태로 응답을 정리함
- 존재하지 않거나 서버에 설치되지 않은 `compiler` id를 보내면 `CompilerRegistry.resolve()`가
  `IllegalArgumentException`을 던지고, 역시 `GlobalExceptionHandler`가 400으로 변환함

---

## `CompilerProperties` / `CompilerRegistry`
**파일**: [`config/CompilerProperties.java`](../backend/src/main/java/com/compareopt/optvisualizer/config/CompilerProperties.java),
[`service/CompilerRegistry.java`](../backend/src/main/java/com/compareopt/optvisualizer/service/CompilerRegistry.java)

`application.yml`의 `compiler.options` 목록(컴파일러 id/label/language/toolchain/후보 실행 파일들)을
`CompilerProperties`가 바인딩하고, `CompilerRegistry`가 서버 시작 시 각 후보에 대해
toolchain이 정한 버전 확인 명령(지금은 모두 `{candidate} --version`)을 실행해보며 실제로 동작하는 첫 번째 후보를 채택합니다.
어떤 후보가 채택됐는지는 시작 로그(`컴파일러 'gcc'(GNU GCC) → gcc-16`)에 남습니다.

- `language`(예: `c`)와 `toolchain`(예: `clang`, `gcc`)은 필수입니다. 둘 중 하나라도 빠지면 서버가 시작 단계에서
  `IllegalStateException`으로 멈춥니다. 기본값을 두면 새 언어를 추가하다 빠뜨렸을 때 엉뚱한 언어로 컴파일되어
  원인을 찾기 어렵기 때문입니다. 값의 의미는 아래 [`toolchain` 패키지](#toolchain-패키지--언어컴파일러마다-다른-부분) 참고.

- 후보는 두 종류입니다. `candidates`는 모든 OS에서 시도하고, `os-candidates.{windows|macos|linux}`는
  그 OS에서만 `candidates` 뒤에 이어서 시도합니다. `Option.candidatesFor(HostOs)`가 이 순서대로 합쳐서
  돌려주며, 빈 값은 건너뜁니다.
  - 후보에는 PATH에 있는 실행 파일 이름(`gcc-16`)과 절대 경로(`C:\msys64\ucrt64\bin\gcc.exe`)를 모두 쓸 수 있습니다.
  - `candidates` 맨 앞의 `${OPTVIS_CLANG:}` / `${OPTVIS_GCC:}` / `${OPTVIS_CLANGXX:}` / `${OPTVIS_GXX:}` / `${OPTVIS_RUSTC:}`는
    환경 변수로 경로를 직접 지정하는 자리입니다. 환경 변수가 없으면 빈 값이 되어 건너뜁니다.
  - rustc의 `os-candidates`에 있는 `${user.home}`은 서버를 띄운 사용자의 홈 디렉터리로 바뀝니다. rustup은 셸 설정 파일로
    `~/.cargo/bin`을 PATH에 넣기 때문에, 셸을 거치지 않고 서버를 띄우면 PATH에 없을 수 있어서 기본 위치를 후보로 둡니다.
- `toolDirOf(executable)` / `ResolvedCompiler.toolDir()`: 절대 경로로 찾은 컴파일러면 그 실행 파일이 들어 있는
  디렉터리를 돌려줍니다 (PATH의 이름으로 찾았으면 `null`). 이 디렉터리는 컴파일러와, 그 컴파일러로 만든
  프로그램을 실행할 때 자식 프로세스의 PATH 맨 앞에 추가됩니다. Windows의 MinGW/MSYS2 GCC는 `gcc.exe`가
  띄우는 `cc1.exe`가 필요한 DLL을 PATH에서 찾기 때문에, 이게 없으면 `--version`은 되는데 실제 컴파일은 실패합니다.

- 버전 확인 명령이 성공해도 `Toolchain.versionMismatch()`가 그 출력을 거부하면 건너뜁니다.
  예를 들어 `toolchain: gcc`는 버전 출력에 `"clang"`이 포함되면 건너뜁니다. macOS의 `/usr/bin/gcc`가 실제로는
  Apple clang의 별칭이라, 그대로 두면 진짜 GCC가 없는데도 "GNU GCC 사용 가능"으로 오탐되기 때문입니다.
- 버전 확인은 후보마다 30초까지 기다립니다(`PROBE_TIMEOUT`). 새로 켠 컴퓨터에서는 큰 실행 파일의 첫 실행이 백신 검사나 디스크 읽기로 10초를 넘길 수 있기 때문입니다.
  시간을 넘기면 실패로 보고 다음 후보로 넘어가고, 후보가 모두 실패하면 그 컴파일러는 서버를 다시 켤 때까지 쓸 수 없습니다.
- `ResolvedCompiler`는 id/label/실행 파일 경로와 함께 그 컴파일러의 `Language`와 `Toolchain`을 들고 있어서,
  `CompilerService`는 이것만 보고 어떤 파일 이름과 인자로 컴파일할지 정합니다.
- `resolve(id)`가 이 프로젝트에서 유일하게 사용자 입력을 실제 실행 파일 경로로 바꿔주는 지점입니다.
  등록되지 않았거나 사용 불가능한 id면 `IllegalArgumentException`을 던집니다 — 사용자가 보낸
  임의 문자열이 그대로 `ProcessBuilder` 커맨드로 흘러들어가는 것(명령어 인젝션)을 막는 화이트리스트 역할.

---

## `CompilerService`
**파일**: [`backend/src/main/java/.../service/CompilerService.java`](../backend/src/main/java/com/compareopt/optvisualizer/service/CompilerService.java)

이 프로젝트의 핵심 로직. 선택된 컴파일러를 서브프로세스로 호출해서 실제 컴파일(및 옵트인 시 실행)을 수행합니다.
언어·컴파일러마다 달라지는 소스 파일 이름, 레벨 목록, 컴파일러 인자는 직접 정하지 않고
`ResolvedCompiler`가 들고 있는 `Language`와 `Toolchain`에서 받아 씁니다. 아래 명령 예시는
clang/gcc(`GccStyleToolchain`) 기준이고, rustc의 명령은 [`RustcToolchain`](#rustctoolchain)에 있습니다.

### `compareOptimizationLevels(String sourceCode, String compilerId, boolean runBenchmark) : List<OptimizationResult>`
전체 흐름을 총괄하는 메서드.
1. `CompilerRegistry.resolve(compilerId)`로 사용할 컴파일러의 실제 경로, 언어, toolchain을 얻고,
   `Toolchain.forSource(sourceCode)`로 이 코드에 맞는 toolchain을 고름 (실패 시 예외)
2. `Files.createTempDirectory()`로 임시 작업 디렉터리 생성
3. `buildLevels()`로 모든 레벨을 컴파일(실행 시간도 잴 때는 실행 파일까지 만듦)
4. 레벨 순서대로 `benchmarkExecution()`으로 실행 시간을 재고(실행 파일이 있을 때만), 결과에 `setCompilerInfo()`로 어떤 컴파일러를 썼는지 기록.
   실행 시간은 컴파일이 모두 끝난 뒤에 한 레벨씩 잼 — 다른 프로세스와 CPU를 나눠 쓰면 측정값이 흔들리기 때문
5. 예외 발생 시(임시 디렉터리 생성 실패 등) 모든 레벨을 실패 결과로 채움
6. `finally` 블록에서 `cleanup()`으로 임시 파일 반드시 삭제

### `buildLevels(...) : List<LevelBuild>` (private)
`Toolchain.levels()`(clang/gcc는 `O0`~`O3`의 `OptLevel`)를 모두 컴파일해서 그 순서대로 돌려줌. 레벨끼리는 서로 기다릴 이유가 없어서 **동시에** 컴파일합니다.
1. 레벨마다 작업 디렉터리(`level-O0`, `level-O1` …)를 따로 만들고 사용자 코드를 `Language.sourceFileName()`
   (C는 `input.c`, C++은 `input.cpp`, Rust는 `input.rs`)으로 저장.
   rustc는 레벨과 상관없이 같은 이름의 중간 파일(`input.input.{해시}-cgu.0.rcgu.o`)을 작업 디렉터리에 써서, 한 곳에서 동시에 컴파일하면 서로 덮어쓰기 때문
2. 이 서버에서 처음 쓰는 컴파일러면 첫 레벨만 먼저 `buildLevel()`로 컴파일. 새로 켠 컴퓨터에서는 컴파일러와 표준 라이브러리를 디스크에서 읽는
   첫 컴파일이 수십 초 걸릴 수 있는데(CI의 새 VM에서 rustc 약 78초), 모든 레벨을 한꺼번에 시작하면 다 같이 시간 초과되어 다시 해 볼 근거가 남지 않음.
   첫 레벨이 읽어 둔 파일은 OS 캐시에 남아서 나머지는 금방 끝남. 첫 레벨에서 `Toolchain.correctedBy()`로 바로잡은 toolchain이 있으면 나머지 레벨에도 그것을 씀
3. 남은 레벨을 스레드 풀에서 동시에 `buildLevel()` (동시에 띄우는 컴파일러 수는 CPU 코어 수 이하).
   첫 레벨도 시간 초과였으면 디스크가 아주 느린 상황이라 예전처럼 한 레벨씩 함
4. 한 레벨이라도 성공하면 그 컴파일러를 "캐시에 올라온 컴파일러"(`warmedUpCompilers`)로 기억해서, 다음 요청부터는 첫 레벨부터 모두 동시에 컴파일함
5. `retryTimedOutLevels()`: 다른 레벨이 성공했으면 컴파일 시간 초과로 실패한 레벨을 한 레벨씩 한 번 더 컴파일. 첫 컴파일이 디스크를 읽느라,
   또는 동시에 컴파일한 레벨끼리 CPU를 나눠 쓰느라 제한 시간을 넘겼을 수 있기 때문. 모든 레벨이 시간 초과면 정말 오래 걸리는 코드이므로 다시 하지 않음

결과의 컴파일 시간(`compileTimeMs`)은 그 레벨의 명령이 걸린 시간이라, 동시에 컴파일하는 다른 레벨과 CPU를 나눠 쓴 만큼 혼자 컴파일할 때보다 조금 길게 나올 수 있습니다.

### `buildLevel(...) : LevelBuild` / `compileLevel(...) : OptimizationResult` (private)
특정 최적화 레벨 하나를 그 레벨의 작업 디렉터리에서 컴파일. `buildLevel()`은 `compileLevel()`이 실패했을 때 `Toolchain.correctedBy()`가
다른 toolchain을 돌려주면 그것으로 한 번 더 컴파일하고, 실행 시간도 잴 때는 `linkExecutable()`로 실행 파일을 만듭니다.
`compileLevel()`이 하는 일:
1. `Toolchain.codegenCommands()`의 첫 번째 명령으로 어셈블리 생성 (`{compiler} -S -O{level} input.c -o out.s`).
   clang/gcc는 이 명령에 remark 인자가 붙어서 최적화 remark도 함께 출력됨
   - 실패 시 컴파일러 출력을 그대로 담아 `OptimizationResult.failure()` 반환
2. 남은 명령으로 오브젝트 파일 생성 (`{compiler} -c out.s -o out.o` — 방금 만든 어셈블리를 어셈블만 함. 크기 비교용, `main()` 없어도 동작).
   rustc는 첫 번째 명령이 둘 다 만들어서 남은 명령이 없음
3. 오브젝트 파일 크기(`Files.size()`)를 읽고, 실패해도 결과는 보여주고 크기만 0으로 둠(로그에 경고를 남김)
4. 어셈블리 파일 내용을 문자열로 읽어서(줄바꿈은 `\n`으로 통일) `OptimizationResult.success()` 생성하고,
   `Toolchain.assemblyNotice()`가 돌려준 안내 문구가 있으면 `notice`에 붙임
5. `Toolchain.remarks()`로 1번 명령의 출력에서 최적화 remark를 뽑아 `remarks`에 붙임.
   레벨당 200건(`MAX_REMARKS_PER_LEVEL`)까지만 담고 나머지는 개수만 `omittedRemarkCount`에 남김
   (함수가 수백 개인 코드는 인라이닝 remark만 수천 건이 됨). remark를 내지 않는 계열(rustc)이면 `remarks`는 `null`로 남음

### `linkExecutable(...)` / `benchmarkExecution(...) : ExecutionOutcome` (private)
`linkExecutable()`은 `Toolchain.executableArgs()`로 실행 파일을 만듭니다(성공해야 `main()`이 있다는 뜻).
clang/gcc는 `compileLevel()`이 만든 오브젝트를 링크만 하고(`{compiler} out.o -o out.exe`), rustc는 소스부터 다시 빌드합니다.
`benchmarkExecution()`은 그 실행 파일을 `BENCHMARK_RUNS`(5)번 실행해서 걸린 시간(나노초) 중 최솟값을 채택합니다.
- 링크 실패(대개 `main()` 없음) 시 컴파일 자체는 성공으로 두고 `executionError`만 채움 — 순수 함수만
  있는 코드에서도 어셈블리/크기 비교는 그대로 볼 수 있게 하기 위함
  - `linkFailureMessage()`가 안내 문구 뒤에 링커의 원본 출력(최대 1,000자)을 붙여줌. 링커가 없는 환경
    (예: Windows에 LLVM만 설치한 경우)에서 원인을 알 수 있게 하기 위함
- 한 번이라도 `EXECUTION_TIMEOUT_SECONDS`(5초) 안에 끝나지 않으면 즉시 타임아웃 에러로 처리
- `Toolchain.executionFailure(exitCode)`가 이유를 돌려주면 실행 시간을 버리고 `executionError`에 그 이유를 담음.
  도중에 죽은 프로그램의 시간은 끝까지 돈 레벨과 비교할 수 없기 때문입니다 (Rust는 레벨 0에서만 오버플로로 패닉이 날 수 있음)
- 실행 파일 이름은 OS와 무관하게 `out-O{n}.exe` (Windows는 확장자가 필요하고, 다른 OS는 있어도 무해)

### `runCompiler(Path workDir, ResolvedCompiler compiler, List<String> args) : ProcessResult` (private)
컴파일러를 임시 작업 디렉터리 **안에서** 실행하는 헬퍼. 실제 프로세스 실행은 `ProcessRunner.run()`에 위임.
- 작업 디렉터리를 `workDir`로 두고 파일은 상대 경로(`input.c`, `out-O0.s`)로만 넘김. 그래서 에러 메시지에
  서버 임시 디렉터리의 절대 경로 대신 `input.c:1:16: error: ...`처럼 파일 이름만 찍히고(별도 치환 불필요),
  임시 디렉터리 경로에 공백이나 한글이 있어도 컴파일러 인자로 전달되지 않음
- **30초 타임아웃**(`COMPILE_TIMEOUT_SECONDS`) — 초과하면 `"컴파일 시간 초과"` 메시지를 담은 실패 결과 반환.
  새로 켠 컴퓨터의 첫 컴파일은 컴파일러와 표준 라이브러리를 디스크에서 읽느라 짧은 코드도 10초를 넘길 수 있어서 넉넉하게 잡음.
  명령마다 적용됨. 모든 레벨이 시간 초과되는 코드는, 처음 쓰는 컴파일러라 첫 레벨부터 시간 초과면 한 레벨씩 하므로 레벨 수만큼 기다리고
  (Rust는 6개 레벨이라 약 3분), 이미 써 본 컴파일러면 레벨을 동시에 컴파일하므로 약 30초 기다림
- 프로세스를 시작하지 못한 경우(`IOException`)에도 예외 대신 사유를 담은 실패 결과를 반환

### `cleanup(Path workDir)` / `deleteRecursively(Path dir)` (private)
`Files.walk()`로 임시 디렉터리 내 모든 파일/디렉터리를 순회하며 삭제.
파일을 먼저 지우고 디렉터리를 나중에 지워야 하므로 경로를 역순 정렬 후 삭제.
Windows에서는 방금 종료된 실행 파일이 백신 검사 등으로 잠깐 잠겨 삭제가 실패할 수 있어서,
남은 파일이 있으면 100ms 간격으로 최대 3번까지 다시 시도.

---

## `toolchain` 패키지 — 언어/컴파일러마다 다른 부분
**파일**: [`toolchain/Language.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/Language.java),
[`toolchain/OptLevel.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/OptLevel.java),
[`toolchain/ToolchainKind.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/ToolchainKind.java),
[`toolchain/Toolchain.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/Toolchain.java),
[`toolchain/GccStyleToolchain.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/GccStyleToolchain.java),
[`toolchain/RustcToolchain.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/RustcToolchain.java),
[`toolchain/OptRemark.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/OptRemark.java),
[`toolchain/RemarkFormat.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/RemarkFormat.java),
[`toolchain/RemarkTopic.java`](../backend/src/main/java/com/compareopt/optvisualizer/toolchain/RemarkTopic.java)

`platform` 패키지가 OS 차이를 모아두듯, 이 패키지는 언어와 컴파일러 계열의 차이를 모아둡니다.
`CompilerService`에는 `if (gcc)`나 `if (rust)` 같은 분기를 두지 않고, 새 언어/컴파일러는 이 패키지에 추가합니다.

### `Language` (enum)
`application.yml`의 `language` 값 (`C`, `CPP`, `RUST`). 언어마다 세 가지만 정합니다.
- `id()`: API 응답과 화면에서 쓰는 식별자 (`"c"`, `"cpp"`, `"rust"`)
- `label()`: 화면의 언어 목록에 보여줄 이름 (`"C"`, `"C++"`, `"Rust"`)
- `sourceFileName()`: 임시 디렉터리에 저장할 파일 이름 (`input.c`, `input.cpp`, `input.rs`). 컴파일러가 확장자로 언어를 판단하기 때문에 언어마다 다릅니다.

### `OptLevel` (record)
`(id, label, description)` — 비교할 최적화 레벨 하나. `id`는 컴파일러 인자와 출력 파일 이름에(`O2` → `-O2`, `out-O2.s`),
`label`은 화면의 열 제목에(`-O2`), `description`은 열 제목 옆 ⓘ 툴팁에 쓰입니다. 화면은 이 값을 응답에서 그대로
가져다 쓰므로, 레벨이 다른 언어(Rust의 `opt-level=2`처럼)를 추가해도 프론트엔드를 고칠 필요가 없습니다.

### `ToolchainKind` (enum)
`application.yml`의 `toolchain` 값. 각 값은 `toolchain()`으로 실제 `Toolchain` 구현을 돌려줍니다.
- `CLANG`: `GccStyleToolchain`, 버전 출력 확인 없음
- `GCC`: `GccStyleToolchain`, 버전 출력에 `clang`이 보이면 거부 (macOS의 clang 별칭 `gcc`를 걸러냄)
- `RUSTC`: `RustcToolchain`

실행 파일 이름은 정하지 않으므로, 같은 계열이라도 언어에 따라 다른 실행 파일(예: `clang` / `clang++`)을
`candidates`에 적어주면 됩니다.

### `Toolchain` (interface)
컴파일러 계열마다 달라지는 "명령줄 만드는 법". 돌려주는 인자에는 실행 파일 경로를 넣지 않습니다
(경로는 `CompilerRegistry`가 찾아서 `CompilerService`가 맨 앞에 붙임). 파일 이름은 모두 작업 디렉터리 기준 상대 경로입니다.
- `levels()`: 비교할 최적화 레벨(`OptLevel`) 목록. 결과도 이 순서로 만들어지고, 화면은 첫 번째 레벨을 비교 기준으로 삼음
- `versionArgs()` / `versionMismatch(output)`: 설치 확인에 쓸 인자와, 그 출력이 이 계열의 컴파일러가 맞는지 판정
  (맞으면 empty, 아니면 건너뛰는 이유 — 시작 로그에 남음)
- `forSource(sourceCode)`: 이 코드를 컴파일할 때 쓸 Toolchain. 기본은 자기 자신이고, 코드 모양에 따라 명령줄이 달라지는 계열만 재정의
- `correctedBy(compilerOutput)`: 컴파일 실패 출력을 보고 `forSource()`의 선택이 틀렸으면 다시 컴파일할 Toolchain을 돌려줌. 기본은 없음.
  `CompilerService`가 그 레벨을 다시 컴파일하고, 처음 컴파일한 레벨에서 바로잡았으면 남은 레벨에도 같은 Toolchain을 씀
- `codegenCommands()`: 어셈블리와 오브젝트 파일을 만드는 명령들의 인자. 진입점이 없어도 성공해야 함.
  첫 번째 명령이 어셈블리를 만들고, 실패하면 컴파일 실패가 됨. 뒤의 명령은 앞 명령이 만든 파일을 써도 됨.
  한 번에 둘 다 만드는 컴파일러는 명령을 하나만 돌려줌. 레벨마다 작업 디렉터리가 따로 있고 레벨끼리 동시에 실행될 수 있음
- `executableArgs()`: 실행 파일을 만드는 인자. 진입점이 없으면 실패해도 됨 (실행 측정만 건너뜀).
  `codegenCommands()`가 성공한 뒤 같은 작업 디렉터리에서 실행하므로 그 레벨의 오브젝트 파일을 링크해도 됨
- `executionFailure(exitCode)`: 종료 코드를 보고 실행 시간을 믿을 수 없으면 이유를 돌려줌. 기본은 따지지 않음
  (C는 계산 결과를 `main`의 반환값으로 쓰는 코드가 흔해서 0이 아니어도 정상 실행일 수 있음)
- `assemblyNotice(assembly)`: 컴파일은 성공했지만 결과를 볼 때 알아야 할 내용이 있으면 돌려줌. 기본은 없음
- `remarks(compilerOutput, sourceFile)`: `codegenCommands()` 첫 번째 명령의 출력에서 최적화 remark(`OptRemark`)를 뽑음.
  기본은 empty(remark를 내지 않는 계열)이고, 이때 화면은 remark 영역을 그리지 않음.
  remark를 내지만 이번에는 하나도 없었다는 뜻의 빈 목록과 구분함

### `GccStyleToolchain`
clang과 GCC처럼 GCC식 명령줄(`-S`, `-c`, `-O2`)을 쓰는 컴파일러. C++의 clang++/g++도 같은 구현을 씁니다.
레벨은 `O0`~`O3`이고, 각 레벨의 초보자용 설명(예전에는 프론트엔드에 있던 툴팁 문구)도 여기서 정합니다.
`ToolchainKind`를 통해서만 만들어지도록 패키지 밖에는 공개하지 않습니다.

clang과 GCC가 다른 부분은 최적화 remark뿐이라 생성자에서 `RemarkFormat`으로 받습니다.
어셈블리를 만드는 명령(`-S`)에 `RemarkFormat.flags()`를 붙이고, `remarks()`는 그 출력을 `RemarkFormat.parse()`에 넘깁니다.

명령은 레벨마다 세 개입니다: `-S`로 어셈블리를 만들고, `-c out.s -o out.o`로 **그 어셈블리를 어셈블만 해서** 오브젝트를 만들고,
실행 시간도 잴 때는 `out.o -o out.exe`로 **그 오브젝트를 링크만** 합니다. 예전에는 셋 다 소스부터 컴파일해서 레벨마다 컴파일을 세 번 했습니다.
어셈블만 해도 오브젝트는 같습니다 — clang, GCC, clang++, g++ 모두 O0~O3에서 크기가 같은 것을 확인했고, GCC는 원래 내부에서 어셈블리를 거쳐
오브젝트를 만듭니다. C++도 clang++/g++ 드라이버로 링크하므로 표준 라이브러리가 함께 링크됩니다.

### `OptRemark` (record)
컴파일러가 한 레벨을 컴파일하면서 남긴 최적화 기록 하나. 어셈블리 모양으로 추정한 것이 아니라 컴파일러가 직접 알려준 내용입니다.
- `kind`: `APPLIED`(최적화를 적용함) 또는 `MISSED`(시도했지만 못 함)
- `line` / `column`: 사용자 소스 코드에서의 위치 (1부터). 컴파일러가 칸을 알려주지 않으면 `column`은 0
- `pass`: 컴파일러가 붙인 패스 이름 (예: `loop-vectorize`). GCC는 알려주지 않아서 `null`
- `topicLabel` / `topicDescription`: 최적화 종류의 한국어 이름과 초보자용 설명 (`RemarkTopic`). 모르는 종류면 `null`
- `message`: 컴파일러가 출력한 원문 메시지
- `count`: 같은 위치에 같은 메시지가 나온 횟수

### `RemarkFormat` (enum)
컴파일러가 remark를 내게 하는 인자와 그 출력을 읽는 방법. 패키지 밖에는 공개하지 않습니다.

| 값 | 붙이는 인자 | 읽는 줄 |
|---|---|---|
| `CLANG` | `-Rpass=.*` `-Rpass-missed=.*` | `input.c:10:18: remark: ... [-Rpass=inline]` (`-Rpass-missed=`면 `MISSED`) |
| `GCC` | `-fopt-info-optimized-missed` | `input.c:9:23: optimized: ...` / `input.c:9:23: missed: ...` |

- 이 인자들은 진단 출력만 늘리고 만들어지는 코드는 바꾸지 않습니다. `CompilerServiceTest`가 인자 없이 직접 컴파일한 어셈블리와 같은지 확인합니다.
- clang의 `-Rpass-analysis`와 GCC의 `-fopt-info-all`은 쓰지 않습니다. 패스별 명령어 수 변화나 벡터화 분석 과정까지 나와서
  짧은 코드도 수백 줄이 됩니다.
- clang의 `-fno-caret-diagnostics`로 소스 인용 줄을 끄지 않습니다. 컴파일 에러에서도 인용이 사라지기 때문입니다.
  인용 줄과 경고는 위 형식에 맞지 않아서 저절로 걸러집니다.

#### `parse(compilerOutput, sourceFile) : List<OptRemark>`
- **사용자 소스 파일의 remark만 남김**: C++ 표준 라이브러리를 쓰면 대부분이 헤더 내부의 인라이닝 기록이고, 화면에서 가리킬 사용자 코드의 줄이 없음
- **clang의 코드 생성 기록은 버림**: `regalloc`(레지스터 할당 비용 통계), `stack-protector`(스택 보호 코드 삽입, `-O0`에서도 나옴),
  `inline-cost`(같은 자리에 `inline` 패스의 remark가 따로 나옴)
- **같은 위치의 같은 remark는 하나로 합치고 `count`를 셈**: 루프를 여러 패스가 다시 볼 때마다 같은 remark가 반복됨
- **소스 줄 순서로 정렬**: 같은 위치의 remark끼리는 컴파일러가 낸 순서를 유지

### `RemarkTopic` (enum)
remark를 초보자가 알아볼 수 있는 최적화 종류로 묶고 한국어 이름과 설명을 붙입니다 (화면의 종류 이름과 마우스를 올렸을 때 나오는 설명).
함수 인라이닝, 루프 벡터화, SLP 벡터화, 루프 펼치기, 중복 계산 제거(GVN), 루프 불변 코드 이동(LICM), 루프 관용구 치환,
루프 삭제, 꼬리 재귀 제거를 압니다. 여기 없는 remark는 버리지 않고 종류 없이 원문만 보여줍니다.
- clang은 remark마다 패스 이름을 알려주므로 그 이름으로 찾음 (`forClangPass`)
- GCC는 패스 이름 없이 메시지만 주므로 메시지에 들어가는 낱말(`inlin`, `vectoriz`, `unroll`, `library call`)로 찾음 (`forGccMessage`).
  GCC의 SLP 벡터화, GVN, LICM 등은 구분하지 못함

### `RustcToolchain`
cargo 없이 rustc를 직접 부르는 구현. 단일 파일과 표준 라이브러리만 쓸 수 있습니다. 레벨은 `0`~`3`, `s`, `z`입니다.

GCC식 컴파일러와 달리 크레이트 종류에 따라 결과가 크게 달라서, `forSource()`가 주석을 지운 코드에서 `fn main`을 찾아 종류를 고릅니다.
문법을 해석하지 않으므로 틀릴 수 있습니다. 메서드, 문자열, 모듈 안의 `fn main`을 잘못 찾으면 rustc가 E0601을 내므로 `correctedBy()`가 라이브러리로 바로잡고,
매크로가 만드는 `main`처럼 놓치면 어셈블리가 비어 안내 문구가 나옵니다.

| 코드 | 명령 | 이유 |
|---|---|---|
| `fn main` 없음 | `--crate-type=lib -C incremental=incr-{n} --emit=asm=out.s,obj=out.o` | 프로그램으로 컴파일하면 E0601 에러. 라이브러리에서는 `pub` 함수만 남음 |
| `fn main` 있음 | `--crate-type=bin --emit=asm=out.s,obj=out.o` | 라이브러리로 컴파일하면 `main`이 쓰이지 않는 비공개 함수가 되어 어셈블리가 통째로 빔 |
| 실행 파일 | `-o out.exe` (코드와 무관하게 항상 프로그램) | `main`이 없으면 실패하고 실행 측정만 건너뜀 |

모든 명령의 앞에는 `--edition=2021 -C symbol-mangling-version=v0 -C codegen-units=1 -C opt-level={n}`이 붙습니다.

- `--edition=2021`: 지정하지 않으면 2015로 컴파일됩니다. 2024는 붙여 넣는 코드에 흔한 `#[no_mangle]`을 에러로 처리하고,
  2021은 배포판 패키지의 오래된 rustc에서도 동작합니다.
- `symbol-mangling-version=v0`: 기본값이 rustc 버전마다 달라서 고정합니다. `legacy`는 안정판에서 지정할 수 없습니다.
- `codegen-units=1`: 실행 시간을 재는 프로그램도 화면에 보여주는 어셈블리와 같은 방식으로 만듭니다.
- `-C incremental`: 증분 빌드가 목적이 아닙니다. rustc는 작은 `pub fn`을 쓰는 쪽 크레이트에서 인라인하려고 미뤄서 레벨 1부터
  어셈블리에서 지워버리는데, 증분 빌드에서는 이 판단을 하지 않습니다. 공식 동작이 아니라서 `CompilerServiceTest`가 실제 rustc로
  이 효과를 확인합니다. 실행 파일에는 쓰지 않습니다. 이 옵션을 쓰면 `.file`의 이름이 레벨마다 달라질 수 있어서 화면의 diff는
  이 줄을 같은 줄로 봅니다 ([frontend.md](./frontend.md) 참고).
- 비공개 함수까지 보여주는 `-C link-dead-code`는 쓰지 않습니다. 인라인되고 남은 제네릭 함수까지 전부 남아서 `pub` 함수와 반복자로 쓴
  예제 기준으로 `opt-level=2`의 어셈블리가 34줄에서 183줄로, 오브젝트 파일이 696B에서 3,264B로 늘어나 비교가 왜곡됩니다.
  대신 함수가 하나도 없으면 `assemblyNotice()`가 이유를 안내합니다 (`.cfi_startproc`이 없는 어셈블리. Windows의 작은 함수에는
  이 지시어도 `.seh_proc`도 없어서, COFF 함수 심볼에 항상 붙는 `.type 32;`로 판정합니다). `#[inline]`이 붙은 함수도 쓰는 쪽 크레이트에서
  만들어지므로 `pub`이어도 라이브러리의 어셈블리에는 없습니다.
- 어셈블리와 오브젝트 파일을 한 번의 실행으로 만듭니다. 따로 실행할 때보다 40%쯤 빠르고(`HashMap`을 쓰는 17줄 프로그램의 6개 레벨 기준
  3.96초에서 2.42초로), 어셈블리와 크기가 같은 컴파일의 결과가 됩니다.
- 레벨 0에서만 오버플로 검사가 켜져 있어서(`cargo build`, `cargo build --release`와 같은 동작) 같은 코드가 레벨 0에서만 패닉으로
  바로 끝날 수 있습니다. 그대로 재면 레벨 0이 가장 빠르게 보이므로 `executionFailure()`가 0이 아닌 종료 코드를 실패로 처리합니다.
- 최적화 remark는 내지 않습니다 (`remarks()`를 재정의하지 않음). rustc에도 `-C remark=all`이 있지만 위치를 얻으려면
  `-C debuginfo`를 켜야 해서 어셈블리와 오브젝트 크기가 달라지고, 나오는 기록도 대부분 표준 라이브러리 내부의 맹글링된 이름이라
  사용자 코드의 줄과 연결되지 않습니다 (rustc 1.98.1에서 확인).

---

## `platform` 패키지 — OS마다 다른 부분
**파일**: [`platform/HostOs.java`](../backend/src/main/java/com/compareopt/optvisualizer/platform/HostOs.java),
[`platform/ProcessRunner.java`](../backend/src/main/java/com/compareopt/optvisualizer/platform/ProcessRunner.java),
[`platform/ProcessResult.java`](../backend/src/main/java/com/compareopt/optvisualizer/platform/ProcessResult.java)

macOS/Linux/Windows에서 동작이 달라지는 코드를 모아둔 패키지. 다른 패키지에는 OS별 분기를 두지 않는 것이 원칙입니다
(배경은 [architecture.md](./architecture.md#os마다-다른-부분은-어디서-처리하나요) 참고).

### `HostOs` (enum)
`WINDOWS`, `MACOS`, `LINUX`, `OTHER`.
- `current()`: 서버가 떠 있는 OS (JVM의 `os.name`으로 판단, 시작 시 한 번만 계산)
- `fromOsName(String)`: `os.name` 문자열을 enum으로 변환. `"Darwin"`에 `"win"`이 들어 있어서 macOS를 먼저 확인함
- `configKey()`: `application.yml`의 `os-candidates`에서 쓰는 키 (`"windows"`, `"macos"`, `"linux"`)

### `ProcessRunner`
외부 프로세스(컴파일러, 사용자 프로그램)를 실행하는 유일한 창구.
두 실행 방식 모두 표준 입력을 바로 닫습니다. 입력을 읽는 프로그램이 오지 않을 입력을 기다리다 "시간 초과"로 처리되지 않도록, 입력이 끝난 상태로 실행됩니다.

#### `run(command, workDir, toolDir, timeout) : ProcessResult`
stdout+stderr를 합쳐서 캡처하며 실행.
- 출력을 파이프가 아니라 **임시 파일**로 받음. 파이프는 버퍼(Windows는 수 KB)가 차면 자식 프로세스가 출력
  대기 상태로 멈추는데, 그러면 경고/에러가 많은 정상 코드가 "시간 초과"로 잘못 처리되기 때문
- `workDir`가 있으면 그 디렉터리를 작업 디렉터리로 지정, `toolDir`가 있으면 자식 프로세스의 PATH 맨 앞에 추가
- 타임아웃 시 **자식 프로세스까지** 강제 종료(`killTree`). 드라이버(`gcc`)만 죽이면 실제 컴파일러(`cc1`)가
  살아남고, Windows에서는 그 프로세스가 임시 파일을 잡고 있어 정리가 실패함
- 실행 파일이 없는 등 프로세스를 시작조차 못 하면 `IOException`을 던짐 (`CompilerRegistry`는 이걸 "설치 안 됨"으로 해석)

#### `runDiscardingOutput(command, workDir, toolDir, timeout) : ProcessResult`
출력을 모두 버리고 실행. 사용자 프로그램의 실행 시간만 잴 때 사용 (출력량이 측정에 영향을 주지 않도록).

#### `prependToPath(env, dir, caseInsensitiveNames)` (package-private)
환경 변수 맵의 PATH 맨 앞에 디렉터리를 추가. Windows는 환경 변수 이름이 보통 `Path`로 들어 있는데
`ProcessBuilder.environment()` 맵은 대소문자를 구분하므로, 기존 키를 찾아 그 키를 갱신함
(그냥 `PATH`로 넣으면 `Path`와 `PATH`가 둘 다 생김).

#### `normalizeNewlines(String) : String` (static)
Windows 도구가 쓰는 `\r\n`을 `\n`으로 통일.

### `ProcessResult` (record)
`(int exitCode, String output, boolean timedOut, long elapsedNanos)` — 프로세스 한 번의 실행 결과.
`succeeded()`는 타임아웃 없이 종료 코드 0으로 끝났는지를 돌려줌.

---

## `HostHeaderFilter`
**파일**: [`backend/src/main/java/.../security/HostHeaderFilter.java`](../backend/src/main/java/com/compareopt/optvisualizer/security/HostHeaderFilter.java)

모든 요청(API와 화면 파일)의 `Host` 헤더를 보고, 이 서버를 가리키는 주소가 아니면 컨트롤러에 넘기지 않고
403으로 거부하는 서블릿 필터. **DNS 리바인딩** 공격을 막습니다.

- **막으려는 공격**: 사용자가 방문한 사이트가 자기 도메인(`evil.example`)의 DNS 응답을 `127.0.0.1`로 바꿔치기하면,
  브라우저는 그 페이지의 스크립트가 `http://evil.example:8080/api/compile`로 보내는 요청을 "같은 출처"로 보고
  사전 요청 없이 이 서버에 보냅니다. 서버가 `127.0.0.1`에서만 듣는 것과 CORS 허용 설정이 없는 것만으로는 막지 못합니다.
  이때도 `Host` 헤더는 주소창의 이름(`evil.example`) 그대로라서 여기서 걸러낼 수 있습니다.
- **통과시키는 Host** (`isAllowed()`): 포트는 떼고, 대소문자와 끝의 점(`localhost.`)은 가리지 않고 비교합니다.
  - `localhost`
  - IP 주소 (`127.0.0.1`, `192.168.0.5`, `[::1]`). IP 주소는 DNS를 거치지 않아 바꿔치기할 수 없으므로 모두 통과시킵니다.
    그래서 `--server.address=0.0.0.0`으로 띄우고 다른 기기에서 IP 주소로 접속하는 것은 설정 없이 됩니다.
  - `optimeter.allowed-hosts`에 적은 이름 (예: `my-mac.local`)
- **거부할 때**: 403과 `{ "message": "이 주소로는 접속할 수 없습니다. ..." }`(`ErrorResponse`)를 응답하고,
  요청 메서드·경로·Host를 WARN 로그로 남깁니다. `localhost.evil.example`, `127.0.0.1.evil.example`처럼
  허용되는 이름을 앞에 붙인 도메인도 거부합니다.
- 판단에는 `request.getServerName()`(`Host`에서 포트를 뗀 값)을 씁니다. 브라우저는 항상 `Host`를 보내지만, `Host`가 없는
  HTTP/1.0 요청이면 서블릿 컨테이너가 서버 자신의 주소나 이름을 대신 돌려주고 그 값으로 같은 판단을 합니다.
- `@Order(Ordered.HIGHEST_PRECEDENCE)`로 다른 필터보다 먼저 실행됩니다.

---

## `StaticResourceConfig`
**파일**: [`backend/src/main/java/.../config/StaticResourceConfig.java`](../backend/src/main/java/com/compareopt/optvisualizer/config/StaticResourceConfig.java)

jar에 묶인 화면 파일을 브라우저가 얼마나 캐시할지 정합니다. `application.yml`의 `spring.web.resources.cache`와 함께 동작합니다.

| 경로 | 예 | `Cache-Control` | 설정한 곳 |
|---|---|---|---|
| `/`, `/index.html` 등 이름에 해시가 없는 파일 | `favicon.svg` | `no-cache` (`Last-Modified` 없음) | `application.yml` (Spring Boot 기본 핸들러 `/**`) |
| `/assets/**` | `index-<hash>.js`, `index-<hash>.css` | `max-age=31536000, immutable` | `StaticResourceConfig.addResourceHandlers()` |
| `/api/**` | | 없음 (바꾸지 않음) | — |

- **왜 필요한가**: `Cache-Control` 없이 `Last-Modified`만 보내면 브라우저가 알아서 몇 시간씩 캐시합니다(추측 캐시).
  그러면 새 버전 jar로 바꿔도 예전 `index.html`이 뜨고, 그 `index.html`이 가리키는 예전 `/assets` 파일은 새 jar에
  없어서 브라우저 캐시에 남아 있지 않으면 빈 화면이 됩니다.
- **`index.html` 등**: 이름이 버전마다 같으므로 `no-cache`로 브라우저가 쓸 때마다 다시 받게 합니다.
  `Last-Modified`(`use-last-modified: false`)도 끕니다. 시각으로 "안 바뀜(304)"을 판단하면, 이전 버전 jar로 되돌린 경우처럼
  파일 시각이 더 이른 쪽으로 바뀌었을 때 캐시해둔 다른 버전의 `index.html`이 그대로 쓰이기 때문입니다.
  파일이 작고 이 PC에서 오므로 매번 통째로 보내도 부담이 없습니다.
- **`/assets/**`**: Vite가 내용 해시를 파일 이름에 넣으므로 같은 주소의 파일은 내용이 바뀌지 않습니다. 그래서 1년 동안
  다시 묻지 않게(`immutable`) 합니다. `assets`는 Vite의 `build.assetsDir` 기본값이라, `frontend/vite.config.js`에서 바꾸면
  여기도 맞춰야 합니다.
- `/assets/` 아래 없는 파일은 404이고 캐시 헤더가 붙지 않습니다 (404가 1년간 캐시되지 않음).
- 확인: `StaticResourceCacheTest`(MockMvc, 테스트용 화면 파일은 `src/test/resources/static`). MockMvc는 `/` → `index.html`
  넘김을 실제로 따라가지 않으므로, jar를 띄워 `/`의 헤더까지 보는 것은 CI의 스모크 테스트가 합니다.

---

## `GlobalExceptionHandler`
**파일**: [`backend/src/main/java/.../exception/GlobalExceptionHandler.java`](../backend/src/main/java/com/compareopt/optvisualizer/exception/GlobalExceptionHandler.java)

`@RestControllerAdvice`로 모든 컨트롤러의 예외를 가로채서, 프론트엔드(`App.jsx`의 `handleCompare`)가
기대하는 `{ message: "..." }` 형태의 JSON으로 통일해 응답한다. 이게 없으면 Spring 기본 에러 응답에는
`message` 필드가 없어서 프론트가 실패 원인을 사용자에게 보여줄 수 없었다.

### `handleValidation(MethodArgumentNotValidException e) : ResponseEntity<ErrorResponse>`
`CompileRequest`의 `@NotBlank`/`@Size` 검증 실패 시 호출. 첫 번째 필드 에러의 메시지(예: "코드를 입력해주세요.")를
뽑아 400 응답으로 반환.

### `handleIllegalArgument(IllegalArgumentException e) : ResponseEntity<ErrorResponse>`
존재하지 않거나 서버에 설치되지 않은 컴파일러를 요청한 경우(`CompilerRegistry.resolve()`). 400 응답으로 반환.

### `handleNotFound(NoResourceFoundException e) : ResponseEntity<ErrorResponse>`
없는 주소를 요청한 경우 404 응답으로 반환. 이 서버는 화면 파일도 함께 내보내므로 브라우저가 없는 파일을
요청하는 일이 흔한데, 따로 처리하지 않으면 아래 catch-all이 500 + 에러 로그로 만들어버리기 때문에 둡니다.

### `handleUnexpected(Exception e) : ResponseEntity<ErrorResponse>`
그 외 처리되지 않은 모든 예외를 잡는 catch-all 핸들러. 로그를 남기고 500 응답으로 반환.

## `ErrorResponse` (DTO)
**파일**: [`backend/src/main/java/.../exception/ErrorResponse.java`](../backend/src/main/java/com/compareopt/optvisualizer/exception/ErrorResponse.java)

`(String message)` — 에러 응답 바디를 표현하는 record.

---

## `CompileRequest` (DTO)
**파일**: [`backend/src/main/java/.../dto/CompileRequest.java`](../backend/src/main/java/com/compareopt/optvisualizer/dto/CompileRequest.java)

요청 바디를 받는 객체. `@NotBlank`, `@Size(max = 20000)` 검증 애너테이션이 붙어있어
컨트롤러의 `@Valid`와 함께 자동으로 유효성 검사가 이루어집니다.
- `compiler` (기본값 `"clang"`): 사용할 컴파일러 id. 여기 담긴 문자열 자체는 검증하지 않고,
  `CompilerRegistry.resolve()`에서 화이트리스트 검증함. 코드의 언어도 이 컴파일러에 설정된 `language`로 정해짐
- `runBenchmark` (기본값 `false`): true면 실행 시간까지 측정 (옵트인)

## `OptimizationResult` (DTO)
**파일**: [`backend/src/main/java/.../dto/OptimizationResult.java`](../backend/src/main/java/com/compareopt/optvisualizer/dto/OptimizationResult.java)

레벨 하나의 컴파일 결과를 담는 응답 객체. 생성자를 `private`으로 막고
`success()` / `failure()` 정적 팩토리 메서드로만 만들 수 있게 해서, 성공/실패 상태에 맞지 않는
필드 조합(예: 실패인데 assembly가 채워짐)이 생기지 않도록 강제합니다.

- `success(OptLevel level, assembly, binarySizeBytes, compileTimeMs)`
- `failure(OptLevel level, errorMessage, compileTimeMs)`
- 두 팩토리 모두 레벨의 `level`(id), `levelLabel`(열 제목), `levelDescription`(툴팁 설명)을 함께 채움.
  결과마다 레벨 정보를 담아두면 화면이 컴파일러 목록과 따로 맞춰보지 않고 바로 그릴 수 있음
- `setCompilerInfo(compilerId, compilerLabel)`: success/failure 여부와 무관하게 항상 호출되어
  어떤 컴파일러로 만든 결과인지 붙여줌
- `setRemarks(remarks, omittedRemarkCount)`: 컴파일러가 남긴 최적화 remark(`OptRemark` 목록, 소스 줄 순서)와,
  너무 많아서 담지 않은 개수. remark를 내지 않는 컴파일러거나 컴파일에 실패했으면 호출되지 않아 `remarks`가 `null`
- `setExecutionResult(executionTimeMs, executionError)`: `runBenchmark=true`일 때만 채워짐.
  둘 다 상호 배타적 — 측정에 성공하면 `executionTimeMs`만, 실패/불가능하면 `executionError`만 채워짐

## `CompilerOptionResponse` (DTO)
**파일**: [`backend/src/main/java/.../dto/CompilerOptionResponse.java`](../backend/src/main/java/com/compareopt/optvisualizer/dto/CompilerOptionResponse.java)

`(String id, String label, boolean available, String language, String languageLabel)` — `GET /api/compilers` 응답 항목 하나.
`language`/`languageLabel`은 `Language.id()`/`label()` (예: `"cpp"` / `"C++"`).
실행 파일 경로는 서버 내부 정보라 일부러 포함하지 않음 (`CompileControllerTest`에서 확인).

---

## `bundle` 프로필 — 실행 가능한 jar 하나 만들기
**파일**: [`backend/pom.xml`](../backend/pom.xml)의 `<profiles>`

사용자에게 배포하는 형태는 프론트엔드까지 들어 있는 jar 하나입니다. `-Pbundle`을 붙이면 Maven이 아래 순서로 만듭니다.

| 순서 | 하는 일 | 담당 |
|---|---|---|
| 1 | 이전 빌드에서 넣어둔 화면 파일(`target/classes/static`) 비우기 | `maven-clean-plugin` |
| 2 | Node.js를 `backend/target/` 아래에 내려받기 (버전은 `frontend.node.version` 속성) | `frontend-maven-plugin` |
| 3 | `frontend`에서 `npm ci` → `npm run build` 실행 (`frontend/dist` 생성) | `frontend-maven-plugin` |
| 4 | `frontend/dist`를 `target/classes/static`으로 복사 → jar에 포함됨 | `maven-resources-plugin` |

```bash
./mvnw -Pbundle package           # target/optimeter-<버전>.jar 생성 → java -jar 로 실행
./mvnw -Pbundle spring-boot:run   # jar를 만들지 않고 소스에서 바로 실행
```

- Node.js를 플러그인이 직접 내려받으므로 빌드하는 PC에 Node.js가 없어도 되고, Windows/macOS/Linux에서 같은 명령으로 동작합니다.
- 기본 빌드(`./mvnw test`, `./mvnw package`)에는 포함되지 않습니다. 백엔드만 고칠 때 프론트엔드 빌드를 기다리지 않기 위함입니다.
- 버전은 `revision` 속성(기본 `0.0.1-SNAPSHOT`)입니다. 릴리스 빌드는 `-Drevision=1.2.3`으로 지정합니다
  ([release.md](./release.md) 참고).
- CI는 세 OS에서 이 프로필로 jar를 만든 뒤 [`.github/scripts/smoke-test.sh`](../.github/scripts/smoke-test.sh)로
  실제로 띄워서 화면(`/`)과 API(`/api/compilers`, `/api/compile`)가 한 서버에서 나오는지, 화면 파일에 캐시 헤더
  (`/`는 `no-cache`, `/assets/*`는 `immutable`)가 붙는지, 낯선 `Host`로 온 요청이 403으로 거부되는지 확인합니다.
