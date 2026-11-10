<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/logo-horizontal-dark.svg">
    <img src="docs/brand/logo-horizontal-light.svg" alt="Optimeter" height="56">
  </picture>
</h1>

[![CI](https://github.com/gwangholee507/optimeter/actions/workflows/ci.yml/badge.svg)](https://github.com/gwangholee507/optimeter/actions/workflows/ci.yml)

**Optimeter**는 컴파일러 최적화 옵션 비교기입니다. 이름은 **opti**mization과 **meter**(측정기)를 합친 말로,
최적화가 코드를 어떻게 바꾸는지 눈으로 보고 숫자로 잰다는 뜻입니다.

C, C++, Rust 코드를 입력하면 **선택한 컴파일러**(Clang/GNU GCC, C++은 Clang++/GNU G++, Rust는 rustc)의
최적화 레벨(C/C++은 `-O0`~`-O3`, Rust는 `opt-level` `0`~`3`, `s`, `z`)을 각각 적용해서
**어셈블리 코드가 어떻게 달라지는지, 컴파일된 코드 크기와 컴파일 시간, (옵션으로) 실제 실행 시간이
얼마나 차이 나는지**를 레벨별 열로 나란히 보여주는 웹앱입니다.

"컴파일러 최적화가 대체 뭘 하는 거지?"를 직접 눈으로 확인해보기 위한 학습용 도구입니다.

## 이런 걸 볼 수 있어요

- `-O0` 대비 `-O1`~`-O3`에서 바뀐 어셈블리 줄이 초록색으로 강조되어, 최적화가
  코드의 어느 부분을 어떻게 바꿨는지 바로 눈에 들어옵니다.
- 열마다 **컴파일러가 직접 남긴 최적화 기록**이 나옵니다. "8행의 `square` 호출을 인라인했다", "7행의 루프는 벡터화하지 못했다"처럼
  무슨 최적화를 했고 무엇을 못 했는지를 소스 줄 번호와 함께 보여줍니다 (C/C++).
- 각 레벨별 **오브젝트 파일 크기**와 **컴파일 시간**을 함께 비교합니다.
- **언어(C/C++/Rust)와 컴파일러(Clang/GCC)를 골라서** 같은 코드를 서로 다른 컴파일러가 어떻게 최적화하는지 비교할 수 있습니다.
- (옵션) **실행 시간 측정**을 켜면 `main()`이 있는 코드를 실제로 링크·실행해서 최적화 레벨 간 실행 속도 차이까지 볼 수 있습니다.
- 문법 오류가 있는 코드를 넣으면 컴파일러의 원본 에러 메시지를 그대로 보여줍니다.

예를 들어 `square(int x) { return x * x; }` 같은 함수는 `-O0`에서는 스택에 값을
저장했다가 다시 읽어 곱하는 비효율적인 어셈블리가 나오지만, `-O1`부터는
`mul w0, w0, w0` 한 줄로 줄어드는 걸 확인할 수 있습니다.
Rust의 `(0..n).map(square).sum()` 같은 반복자 코드는 `opt-level=0`에서 수백 줄이던 어셈블리가
`opt-level=2`에서 수십 줄로 줄어듭니다.

## 기술 스택

| 영역 | 구성 |
|---|---|
| Backend | Spring Boot (Java 17), `clang`/`gcc`(C++은 `clang++`/`g++`, Rust는 `rustc`)를 서브프로세스로 호출해 컴파일·실행 수행 |
| Frontend | React + Vite |
| Compiler | 서버를 실행하는 PC에 설치된 clang + 선택적으로 GNU GCC, C++ 컴파일러, rustc (macOS, Linux, Windows) |
| CI | GitHub Actions — Ubuntu, macOS, Windows에서 테스트 ([워크플로 정의](.github/workflows/ci.yml)) |

## 구조

```
.
├── backend/   # Spring Boot 서버 — 컴파일 실행, 결과 비교 (배포할 때는 화면도 함께 내보냄)
├── frontend/  # React UI — 코드 입력, 결과 렌더링
└── docs/      # 문서 모음 (아래 참고)
```

사용자에게 배포하는 형태는 **jar 파일 하나**입니다. 프론트엔드를 빌드한 결과물을 백엔드 jar 안에 넣어서,
서버 하나가 화면과 API를 함께 내보냅니다.

## 빠르게 실행해보기

### 준비물

macOS, Linux, Windows에서 실행할 수 있습니다 (CI가 세 OS 모두에서 테스트합니다).

- JDK 17 이상
- C 컴파일러: `clang`, 그리고 선택적으로 GNU GCC (설치하면 컴파일러 드롭다운에서 고를 수 있음)
- (선택) C++ 컴파일러: `clang++`, `g++` (설치하면 언어 드롭다운에서 C++을 고를 수 있음)
- (선택) Rust 컴파일러: `rustc` ([rustup](https://rustup.rs/)으로 설치하면 언어 드롭다운에서 Rust를 고를 수 있음)

실행만 할 때는 이 두 가지면 됩니다. Maven과 Node.js는 따로 설치하지 않아도 됩니다.

| OS | 컴파일러 설치 |
|---|---|
| macOS | `xcode-select --install` (clang, clang++), `brew install gcc` (GCC와 g++, 선택) |
| Ubuntu/Debian | `sudo apt-get install clang` (clang, clang++), `sudo apt-get install gcc g++` (GCC와 g++, 선택) |
| Windows | [MSYS2](https://www.msys2.org/) 설치 후 UCRT64 터미널에서 `pacman -S mingw-w64-ucrt-x86_64-clang mingw-w64-ucrt-x86_64-gcc` (C++ 컴파일러 포함) |

> Windows에서 MSYS2를 기본 위치(`C:\msys64`)에 설치했다면 PATH를 따로 설정하지 않아도 서버가 알아서 찾습니다.
> 다른 방법으로 설치했거나 인식이 안 될 때는 [사용자 가이드의 Windows 안내](docs/user-guide.md#windows에서-컴파일러-준비하기)를 참고하세요.

### 방법 1 — jar 파일 내려받아 실행

[Releases](https://github.com/gwangholee507/optimeter/releases) 페이지에 올라온 `optimeter-<버전>.jar`를 내려받아 실행합니다.
(올라온 릴리스가 없다면 방법 2를 사용하세요.)

```bash
java -jar optimeter-<버전>.jar
```

브라우저에서 `http://localhost:8080`을 열면 됩니다. 끝낼 때는 터미널에서 `Ctrl+C`를 누릅니다.

### 방법 2 — 소스에서 바로 실행

저장소를 받은 뒤 명령 하나로 실행합니다. 프론트엔드 빌드에 필요한 Node.js는 빌드가 알아서 내려받습니다.

```bash
cd backend
./mvnw -Pbundle spring-boot:run        # Windows(PowerShell/cmd): .\mvnw.cmd -Pbundle spring-boot:run
```

마찬가지로 `http://localhost:8080`을 엽니다. (처음 실행할 때는 내려받을 것이 많아 몇 분 걸릴 수 있습니다.)

> 서버는 기본적으로 **이 PC에서 오는 접속만** 받습니다. 받은 코드를 컴파일하고 실행까지 하는 프로그램이라,
> 같은 네트워크의 다른 기기가 접속할 수 없도록 한 것입니다. 포트를 바꾸려면 끝에 `--server.port=9090`을 붙입니다.

## 개발할 때

코드를 고치면서 볼 때는 백엔드와 프론트엔드를 따로 띄웁니다. 프론트엔드 개발 서버가 수정 사항을 바로
반영해주고, `/api` 요청은 백엔드로 넘겨줍니다. 이때는 Node.js 20.19 이상 또는 22.12 이상이 필요합니다.

```bash
# 터미널 1 — 백엔드 (포트 8080)
cd backend
./mvnw spring-boot:run        # Windows(PowerShell/cmd): .\mvnw.cmd spring-boot:run
```

```bash
# 터미널 2 — 프론트엔드 개발 서버 (포트 5173)
cd frontend
npm install
npm run dev
```

브라우저에서는 프론트엔드 개발 서버 주소(`http://localhost:5173`)를 엽니다.

## 테스트

```bash
cd backend && ./mvnw test      # Windows: .\mvnw.cmd test
cd frontend && npm test
```

> 백엔드 테스트는 실제 컴파일러를 호출하므로 `clang`이 설치되어 있어야 통과합니다.
> JDK는 17 이상이면 어떤 버전이든 됩니다 (`mvnw`는 `JAVA_HOME`, 없으면 PATH의 `java`를 사용).

## 더 알아보기 (문서)

처음 오셨다면 아래 순서로 읽어보시는 걸 추천합니다.

| 문서 | 내용 |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | **사용자 가이드** — 실행 방법, 화면 사용법, 결과 읽는 법, FAQ (코드 몰라도 됨) |
| [docs/rust.md](docs/rust.md) | Rust 지원 — 코드를 컴파일하는 방식, C/C++과 다른 점, 제한 사항 |
| [docs/architecture.md](docs/architecture.md) | 요청이 프론트엔드 → 백엔드 → clang까지 어떻게 흘러가는지 전체 흐름도 |
| [docs/backend.md](docs/backend.md) | Spring Boot 백엔드 클래스/함수별 설명 |
| [docs/frontend.md](docs/frontend.md) | React 프론트엔드 컴포넌트/함수별 설명 |
| [docs/README.md](docs/README.md) | 문서 전체 목차 |

기여하려는 경우 브랜치/커밋 규칙도 확인해주세요.

| 문서 | 내용 |
|---|---|
| [docs/branching-strategy.md](docs/branching-strategy.md) | Git 브랜치 전략 (GitHub Flow) |
| [docs/commit-convention.md](docs/commit-convention.md) | 커밋 메시지 규칙 (Conventional Commits) |
| [docs/release.md](docs/release.md) | 릴리스 방법 — 태그를 올리면 jar가 자동으로 만들어져 배포됨 |
| [CLAUDE.md](CLAUDE.md) | Claude Code로 작업할 때 지켜야 할 프로젝트 규칙 |

## 참고 자료

- [Clang 공식 문서 — 최적화 옵션](https://clang.llvm.org/docs/CommandGuide/clang.html) — `-O0`~`-O3`이 실제로 어떤 옵션 집합인지
- [rustc 코드 생성 옵션](https://doc.rust-lang.org/rustc/codegen-options/index.html) — Rust의 `-C opt-level`이 받는 값과 다른 옵션들
- [Compiler Explorer (godbolt.org)](https://godbolt.org/) — 더 다양한 컴파일러/아키텍처로 어셈블리를 비교하고 싶다면
- [LLVM 최적화 파이프라인 개요](https://llvm.org/docs/Passes.html) — clang이 내부적으로 어떤 최적화 패스를 거치는지 더 깊게 알고 싶다면

## 참고

- 서버를 실행하는 PC에 설치된 컴파일러를 그대로 사용합니다. 그래서 어셈블리는 그 PC의 OS와
  CPU 아키텍처(ARM64, x86-64 등)에 맞춰 나옵니다. 같은 코드라도 macOS, Linux, Windows에서
  결과가 서로 다른 것이 정상입니다.
- OS마다 달라지는 부분(컴파일러 위치 탐색, 프로세스 실행/종료)이 코드의 어디에 모여 있는지는
  [docs/architecture.md](docs/architecture.md#os마다-다른-부분은-어디서-처리하나요)를 참고하세요.
- 자기 PC에서 혼자 쓰는 것을 전제로 한 프로그램입니다. 별도 샌드박싱 없이 그 PC의 권한으로 컴파일과
  실행을 수행하므로, 서버는 기본적으로 이 PC에서 오는 접속만 받습니다. 여러 사람이 접속하는 서버로
  운영하려면 Docker 등으로 격리 + 리소스 제한을 먼저 추가해야 합니다.
