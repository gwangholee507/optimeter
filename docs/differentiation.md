# 비슷한 도구와 비교한 차별점

이 프로젝트와 비슷한 일을 하는 도구들을 조사하고, 그 도구들과 비교했을 때 **이 프로젝트가 내세울 수 있는 점**,
**내세울 때 조심할 점**, **앞으로 강점을 키우려면 무엇을 하면 좋을지**를 정리한 문서입니다.

> 조사 기준일: **2026-10-19** (웹 검색 기준). 다른 도구의 기능·스타 수·버전은 시간이 지나면 바뀌므로,
> 이 문서를 근거로 무언가를 발표하거나 README에 옮길 때는 다시 확인하세요.

## 한 문장으로

> **설치 한 번으로 내 PC에서, 코드를 밖으로 보내지 않고, `-O0`~`-O3`가 어셈블리를 어떻게 바꾸는지와
> 그 결과 크기·컴파일 시간·실행 시간이 어떻게 달라지는지를 한 화면에서 비교하는 학습용 도구.**

Compiler Explorer를 대체하려는 도구가 아니라, "최적화 레벨이 대체 뭘 바꾸나?"라는 **질문 하나에 특화된 입문 도구**라는
위치를 잡는 것이 가장 정직하고 설득력 있습니다.

## 비슷한 도구 한눈에 보기

| 도구 | 형태 | 핵심 기능 | 이 프로젝트와 겹치는 점 / 다른 점 |
|---|---|---|---|
| [Compiler Explorer](https://github.com/compiler-explorer/compiler-explorer) (godbolt.org) | 웹 서비스, 로컬 설치 가능 (BSD-2, ⭐ 약 19.1k) | 30개 이상 언어, 수많은 컴파일러·아키텍처, 소스↔어셈블리 줄 색 매핑, 출력 필터링, Opt Pipeline·CFG 창, 실행 결과 출력, 창별 컴파일 지표, Quick Bench 연동 | 기능 면에서는 이 프로젝트의 상위 집합에 가깝습니다. 다만 O0~O3를 함께 보려면 컴파일러 창을 4개 띄우고 플래그를 하나씩 입력해야 하고, Diff 창은 두 출력씩 비교합니다. 레벨별 실행 시간 비교는 없습니다. 로컬 설치는 Node + 저장소 clone + (Windows에서는) 컴파일러 설정 파일 수동 편집이 필요합니다 |
| [Quick Bench](https://github.com/FredTingaud/quick-bench-front-end) (quick-bench.com) | 웹 서비스, 로컬은 Docker(Bench Runner) | Google Benchmark로 **코드 조각 2개 이상의 실행 속도** 비교. 자매 도구 Build Bench는 빌드 시간 비교 | 실행 시간을 다루지만 "코드 조각 간" 비교가 중심이고, 레벨 간 비교·어셈블리 diff가 목적이 아닙니다. 벤치마크 API로 코드를 감싸야 합니다 |
| [compopt](https://github.com/Bagodiya/compiler-optimization-visualizer) (Bagodiya/compiler-optimization-visualizer) | Python CLI (MIT, ⭐ 2) | C를 O0~O3로 컴파일해 나란히 표시(`show`), 두 레벨 diff(`diff`), **최적화 10종을 감지해 이름 붙이기**(`annotate`, `--explain`) | **가장 직접적인 경쟁 도구.** 목적이 거의 같습니다. 대신 터미널 전용이라 4열을 보려면 넓은 터미널이 필요하고, 크기·시간 측정은 소개되어 있지 않습니다. 감지 결과는 어셈블리 모양으로 추정한 것이라 틀릴 수 있다고 작성자가 밝히고 있습니다 |
| [asmdiff](https://pypi.org/project/asmdiff/) | Python CLI (MIT, v0.4.1, 2026-10-14) | 함수 단위로 플래그·컴파일러 버전·타깃·소스 리비전 간 어셈블리 비교, 명령어 수 요약, CI용 `--fail-on-growth`, 임베디드 보드 프로필 | 숙련자·임베디드 개발자 대상입니다. **Mach-O(macOS) 어셈블리는 지원하지 않습니다** |
| [OptView2](https://github.com/OfekShilon/optview2) / [LLVM opt-viewer](https://llvm.org/docs/Remarks.html) | CLI → HTML 보고서 (⭐ 152) | clang 최적화 remark(`-fsave-optimization-record`)를 소스에 붙여서 **"왜 최적화가 안 됐는지"** 보여줌 | 어셈블리 비교가 아니라 컴파일러가 남긴 진단을 보는 도구입니다. 방향이 달라서 경쟁보다는 나중에 참고할 기능 아이디어에 가깝습니다 |
| VS Code Disassembly Explorer, C++ 확장의 Disassembly View | 편집기 확장 | 편집기 안에서 디스어셈블리 보기 (C++ 확장은 디버깅 중에만) | 레벨별로 나란히 비교하는 기능은 없습니다 |

검색 범위 안에서는 **"브라우저에서 C 코드를 넣으면 O0~O3 어셈블리를 나란히 보여주고 바뀐 줄을 강조하는 웹앱"**으로
이 저장소 외에 다른 프로젝트를 찾지 못했습니다.

## 내세울 차별점

### 1. 한 번의 클릭으로 네 레벨을 나란히 비교

- **무엇:** 코드를 넣고 "비교하기"를 누르면 `-O0`~`-O3` 결과가 네 열로 나오고, O0 대비 바뀐 줄이 초록색으로 강조됩니다.
- **다른 도구는:** Compiler Explorer는 창 4개를 직접 구성하고 각 창에 플래그를 입력해야 하며, diff는 두 개씩입니다.
  compopt는 같은 비교를 하지만 CLI라 터미널 폭에 따라 4열이 안 나올 수 있습니다.
- **근거:** 줄 비교는 [frontend/src/diff.js](../frontend/src/diff.js)의 LCS 기반 `diffLines`가 담당합니다.
  비교 전에 [frontend/src/assemblyFilter.js](../frontend/src/assemblyFilter.js)가 `.cfi_*`, `.section` 같은 지시어와 디버그·예외 처리 섹션을
  걸러내서(화면의 "지시어 숨기기", 기본값 켜짐) 실제로 실행되는 명령어만 비교합니다.
- **내세울 때:** "설정 없이 바로 보이는 4단 비교"를 첫 화면 스크린샷으로 보여주는 것이 가장 강력합니다.

### 2. 코드 변화와 숫자를 한 화면에서 함께 본다

- **무엇:** 어셈블리 diff와 함께 레벨별 **오브젝트 파일 크기, 컴파일 시간, (옵션) 실행 시간**을 나란히 보여줍니다.
- **다른 도구는:** compopt·asmdiff는 어셈블리(와 명령어 수)만, Quick Bench는 실행 시간만 다룹니다.
  Compiler Explorer는 창마다 컴파일 지표를 보여주지만 레벨 간 실행 시간 비교는 없습니다.
- **근거:** 크기는 `-c`로 만든 오브젝트 파일 크기([CompilerService.java:108](../backend/src/main/java/com/compareopt/optvisualizer/service/CompilerService.java:108)),
  실행 시간은 5번 실행한 값 중 최솟값([CompilerService.java:154](../backend/src/main/java/com/compareopt/optvisualizer/service/CompilerService.java:154))입니다.
- **내세울 때:** [optimization-levels.md](./optimization-levels.md)에 적은 **"O3가 항상 O2보다 빠르지는 않고, 크기는 커진다"**를
  사용자가 같은 화면에서 직접 확인할 수 있다는 점이 학습용 도구로서의 핵심 메시지입니다.

### 3. 내 PC의 실제 CPU에서 측정하고, 코드가 밖으로 나가지 않는다

- **무엇:** 사용자 PC에 설치된 clang/GCC로 컴파일하고, 그 PC의 CPU(Apple Silicon, x86-64 등)에서 실행합니다.
  서버는 기본적으로 `127.0.0.1`에만 열립니다([application.yml](../backend/src/main/resources/application.yml)).
- **다른 도구는:** godbolt.org·quick-bench.com 같은 공개 서비스는 코드를 외부 서버로 보내고, 실행 시간도 공유 서버 CPU에서 잽니다.
- **내세울 때:** "과제 코드나 회사 코드를 올리기 부담스러운 경우", "내 노트북에서 실제로 얼마나 빨라지는지 보고 싶은 경우"에 맞는 도구라고 설명합니다.

### 4. 설치가 가장 가볍다 — jar 하나, 세 OS 지원

- **무엇:** JDK 17과 clang만 있으면 `java -jar` 한 번으로 실행됩니다. Maven·Node.js 설치가 필요 없습니다.
  Windows에서는 MSYS2 기본 경로의 컴파일러를 자동으로 찾고, CI가 Ubuntu·macOS·Windows에서 테스트합니다.
- **다른 도구는:** Compiler Explorer 로컬 설치는 Node + 저장소 clone이 필요하고, Windows에서는 컴파일러를 설정 파일로 직접 등록해야 합니다.
  Quick Bench 로컬 실행은 Docker가 필요합니다. compopt는 Python 3.11+, asmdiff는 macOS 어셈블리를 지원하지 않습니다.
- **내세울 때:** "수업 실습실 PC나 개인 노트북에 5분 안에 설치"처럼 구체적인 설치 경험으로 보여줍니다.

### 5. 한국어로 된 학습 자료가 함께 있다

- **무엇:** README, [사용자 가이드](./user-guide.md), [최적화 레벨 설명](./optimization-levels.md), 컴파일 에러 안내 문구가 모두 한국어입니다.
- **다른 도구는:** 조사한 도구는 모두 영어 기반입니다.
- **내세울 때:** 국내 컴퓨터 구조·컴파일러 수업의 실습 도구로 쓰기 좋다는 점을 강조할 수 있습니다.

## 내세울 때 조심할 점

- **기능 수로 Compiler Explorer와 경쟁하지 않기.** 아키텍처·언어·분석 창 수에서 상대가 되지 않습니다.
  "더 많은 기능"이 아니라 **"이 질문 하나에 더 빨리 답하는 도구"**라고 말하는 것이 맞습니다.
- **실행 시간 숫자를 과장하지 않기.** 측정 구간에 프로세스를 띄우는 시간까지 들어 있습니다
  ([ProcessRunner.java:107](../backend/src/main/java/com/compareopt/optvisualizer/platform/ProcessRunner.java:107)).
  실행이 아주 짧은 코드는 O0와 O3 차이가 이 비용에 묻힐 수 있으니, "반복이 많은 코드에서 의미가 있다"고 함께 적어야 합니다.
- **"바뀐 줄 강조"가 "최적화 설명"은 아니라는 점.** 강조는 *어디가* 바뀌었는지만 보여줍니다. *무슨 최적화가* 일어났는지는
  열마다 나오는 최적화 기록(C/C++)이 알려주지만, 기록은 소스 줄 기준이라 어셈블리의 어느 줄이 그 결과인지까지 이어주지는 않습니다.

## 지금 강점을 깎아먹는 약점

| 약점 | 영향 | 비교 대상 |
|---|---|---|
| 원문 줄 단위 비교 | 레지스터 이름만 바뀌어도 줄 전체가 "추가됨"으로 표시됨 | — |
| 소스↔어셈블리 줄 연결 없음 | 어셈블리 어느 부분이 소스 어느 줄인지 학습자가 직접 찾아야 함 | Compiler Explorer의 핵심 기능 |
| Rust는 최적화 기록 없음 | C/C++은 컴파일러의 remark로 "왜 이렇게 바뀌었나"에 답하지만 Rust는 아직 답하지 못함 | compopt의 `annotate`, OptView2 |
| C/C++/Rust·단일 파일·호스트 아키텍처만, C/C++은 `-Os`/`-Oz` 없음 | C/C++에서 크기 비교 강점을 살릴 레벨이 빠져 있음 (Rust는 `opt-level=s`/`z`로 비교 가능) | asmdiff는 여러 타깃 지원 |

## 향후 발전 방향 (프로젝트 완성 후 검토)

> 현재 진행 중인 다국어 지원(C++/Rust) 계획을 마친 뒤 검토할 항목입니다.
> 위에서부터 **차별점을 키우는 효과가 큰 순서**로 적었습니다.

1. **지시어 필터 + 레지스터 이름을 맞춘 diff** — 차별점 1을 직접 강화합니다.
   지시어 필터는 구현했습니다(화면의 "지시어 숨기기", 기본값 켜짐). 남은 것은 레지스터 이름만 다른 줄을 같은 줄로 보는 diff입니다.
2. **레벨별 최적화 remark 표시** — 구현했습니다(C/C++, 열마다 나오는 "최적화 기록"). clang `-Rpass=.*` / `-Rpass-missed=.*`,
   GCC `-fopt-info-optimized-missed`의 출력을 소스 줄 번호와 함께 보여줘서 "무슨 최적화가 일어났나(또는 왜 안 됐나)"에 답합니다.
   compopt·OptView2가 하는 일을 컴파일러가 직접 남긴 근거로 대신하는 셈이라 추정보다 정확합니다.
   남은 것은 Rust(디버그 정보 없이는 위치가 나오지 않고 이름이 맹글링되어 있음)와, remark를 누르면 소스의 그 줄로 이동하는 연결입니다.
3. **실행 시간 측정 개선** — 빈 프로그램을 같은 방식으로 실행해 프로세스 시작 비용을 따로 보여주거나 빼고,
   반복 횟수와 편차(최소/중앙값)를 함께 표시합니다. 차별점 2·3의 신뢰도를 높입니다.
4. **`-Os`/`-Oz` 열 추가** — "O3는 크기가 커진다"를 크기 최적화 레벨과 바로 비교할 수 있게 되어 차별점 2가 선명해집니다.
5. **소스↔어셈블리 줄 연결** — `-g`로 컴파일하면 어셈블리에 들어가는 `.loc` 지시어로 줄 번호를 얻을 수 있습니다. Compiler Explorer와의 가장 큰 격차를 줄입니다.
6. **결과 내보내기 (HTML/JSON)** — 로컬 도구라 공유 링크를 만들 수 없으니, 비교 결과를 파일로 저장해 과제 제출·발표 자료로 쓸 수 있게 합니다. 코드가 밖으로 나가지 않는다는 차별점 3을 유지하면서 공유 문제를 해결합니다.
7. **예제 코드 모음** — "O3에서 벡터화되는 루프", "꼬리 호출 최적화", "상수 폴딩" 같은 예제를 고를 수 있게 해서 학습용 도구로서의 차별점 5를 강화합니다.

## 출처

- [Compiler Explorer 저장소](https://github.com/compiler-explorer/compiler-explorer) · [WhatIsCompilerExplorer.md](https://github.com/compiler-explorer/compiler-explorer/blob/main/docs/WhatIsCompilerExplorer.md) · [WindowsNative.md](https://github.com/compiler-explorer/compiler-explorer/blob/main/docs/WindowsNative.md)
- [Quick Bench front-end](https://github.com/FredTingaud/quick-bench-front-end)
- [Bagodiya/compiler-optimization-visualizer (compopt)](https://github.com/Bagodiya/compiler-optimization-visualizer)
- [asmdiff (PyPI)](https://pypi.org/project/asmdiff/)
- [OptView2](https://github.com/OfekShilon/optview2) · [LLVM Remarks 문서](https://llvm.org/docs/Remarks.html)
- [VS Code에서 디스어셈블리 보기 (learncgames)](https://learncgames.com/tag/disassembly/)
