# 프론트엔드 (React + Vite)

## `diff.js`
**파일**: [`frontend/src/diff.js`](../frontend/src/diff.js)

외부 라이브러리 없이 직접 구현한 줄 단위 diff 유틸리티.

### `diffLines(baseLines, targetLines) : { lineStates, addedCount, removedCount } | null`
- **입력**: `baseLines`(기준이 되는 줄 배열, 기본은 O0 어셈블리이고 화면에서 고른 비교 기준 레벨), `targetLines`(비교 대상 줄 배열).
  "지시어 숨기기"가 켜져 있으면 `filterAssembly()`로 걸러낸 뒤의 줄이 들어옵니다.
  `.file` 지시어는 내용과 상관없이 같은 줄로 봅니다. 코드가 아니라 컴파일 단위의 이름이고, rustc는 `-C incremental`을 쓰면 이 이름이 레벨마다 달라질 수 있습니다
  (지시어를 숨기면 `.file` 줄 자체가 빠지므로 "지시어 숨기기"를 끈 경우에만 의미가 있습니다)
- **알고리즘**: 앞뒤의 같은 줄을 덜어내고 남은 줄을 정수로 바꾼 뒤, LCS(최장 공통 부분 수열)를 DP 테이블(`Uint16Array`)로 계산하고 되짚어가며(backtrack)
  - `base[i] === target[j]`면 `'same'`
  - 아니고 `base`에서 삭제하는 게 유리하면(`dp[i+1][j] >= dp[i][j+1]`) `removedCount` 증가
  - 그 외에는 `target`에 새로 추가된 줄이므로 `'added'`
- **출력**:
  - `lineStates`: `targetLines`의 각 줄에 대응하는 `'same' | 'added'` 배열 (인덱스가 `targetLines` 줄 번호와 1:1 대응)
  - `addedCount`: `targetLines`에만 있는(새로 생기거나 바뀐) 줄 수
  - `removedCount`: `baseLines`에만 있고 `targetLines`에는 없는(사라진) 줄 수
  - 덜어낸 뒤의 표가 `MAX_DIFF_CELLS`(1억 5천만 칸, 약 300MB)를 넘으면 `null`
- **복잡도**: O(n·m). Rust는 짧은 프로그램도 `opt-level=0`에서 수만 줄이 나오므로 표를 줄 수의 곱에 맞춰 무한정 키울 수 없습니다.
  `HashMap`을 쓰는 17줄 프로그램(16,767줄과 3,929줄 비교)은 한 쌍에 약 0.2초 걸립니다.
  화면에서는 화면 스레드가 아니라 Web Worker에서 부릅니다 (아래 `diffRunner.js`).

---

## `diffRunner.js` / `diff.worker.js`
**파일**: [`frontend/src/diffRunner.js`](../frontend/src/diffRunner.js), [`frontend/src/diff.worker.js`](../frontend/src/diff.worker.js)

`diffLines()`를 Web Worker에서 돌리는 실행기. 비교는 O(n·m)이라 어셈블리가 수천 줄이면 한 쌍에 수백 ms가 걸리고,
화면 스레드에서 하면 그동안 화면이 멈춥니다 (표준 헤더를 쓰는 C++ 프로그램에서 "지시어 숨기기"를 끄면 세 열 비교에 약 1.4초).
`diff.worker.js`는 `{ base, target }` 두 어셈블리 텍스트를 받아 `diffLines()`의 결과를 그대로 돌려주는 worker입니다.

### `createDiffRunner({ createWorker, maxConcurrent }) : (base, target) => { promise, cancel }`
- 비교마다 worker를 새로 띄우고 결과를 받으면 닫습니다. 그래서 더 기다릴 필요가 없어진 비교(기준 레벨이나 "지시어 숨기기"를 바꾼 경우 등)는
  `cancel()`로 계산 도중에도 바로 멈춥니다. `cancel()`한 비교의 `promise`는 이행되지 않습니다
- 동시에 띄우는 worker는 `MAX_CONCURRENT_DIFFS`(2)개까지이고, 나머지는 기다렸다가 앞에서부터 시작합니다.
  비교 하나가 LCS 표에 수백 MB까지 쓸 수 있어서(`MAX_DIFF_CELLS`) 적게 둡니다
- worker를 띄우지 못하거나 worker가 실패하면 `console.warn`을 남기고 화면 스레드에서 계산합니다 (느려도 결과는 같음)
- `createWorker`, `maxConcurrent`는 테스트에서 가짜 worker를 넣으려고 받습니다. 화면은 기본값으로 만든 `runDiff`를 씁니다

---

## `assemblyFilter.js`
**파일**: [`frontend/src/assemblyFilter.js`](../frontend/src/assemblyFilter.js)

어셈블리에서 실행되는 명령어와 데이터만 남기고, 어셈블러·링커에게 주는 정보처럼 사람이 읽을 필요가 없는 줄을 걸러내는 순수 함수.
화면의 "지시어 숨기기" 체크박스(기본값 켜짐)가 이 함수를 씁니다. `assemblyFilter.test.js`로 테스트합니다.

### `filterAssembly(assembly) : string`
- **입력**: 어셈블리 텍스트 한 덩어리 (`OptimizationResult.assembly`)
- **출력**: 남길 줄만 원래 순서대로 `\n`으로 이은 텍스트. 남긴 줄의 내용(들여쓰기, 줄 끝 주석)은 바꾸지 않습니다
- **숨기는 줄**
  - 지시어(`.`으로 시작하는 줄)와 `@feat.00 = 0` 같은 기호 정의 — `.cfi_*`, `.p2align`, `.globl`, `.section`, `.loc`, `.seh_*`, `.def` 등
  - 주석만 있는 줄과 빈 줄 — 주석 기호는 x86의 `#`, Apple arm64의 `;`, Linux arm64의 `//`
  - 아무 명령어나 데이터도 가리키지 않는 **내부 레이블** — `.Lfunc_end0:`(`.size`에서만 씀), `Lloh0:`(`.loh`에서만 씀) 등.
    ELF와 COFF는 `.L`로 시작하는 레이블, Mach-O(macOS)는 `L`이나 `l`로 시작하는 레이블이 내부 레이블입니다.
    Mach-O인지는 `.subsections_via_symbols`, `.build_version` 같은 macOS 전용 지시어가 있는지로 판단합니다
    (Linux는 사용자 함수 이름이 `_` 없이 나오므로 `lerp:` 같은 함수를 내부 레이블로 오인하지 않도록)
  - 숨긴 내부 레이블에 딸린 데이터
  - 디버그 정보(`.debug*`, `__DWARF`), 예외 처리 표(`.gcc_except_table`, `__gcc_except_tab`), 되감기 정보(`.eh_frame`, `.xdata`, `.pdata`,
    `__compact_unwind`), `.note*`, `.comment`, `.llvm_addrsig` 섹션의 내용 전체.
    섹션은 `.section`, `.pushsection`/`.popsection`, `.previous`, `.text`/`.data`/`.cstring` 같은 짧은 지시어로 추적합니다
- **남기는 줄**
  - 명령어, 그리고 `레이블: 명령어`처럼 한 줄에 쓴 명령어
  - 내부 레이블이 아닌 레이블(함수 이름, 전역 변수 이름)과, 남은 명령어나 데이터가 이름을 쓰는 내부 레이블
  - 데이터를 만드는 지시어(`.byte`, `.long`, `.quad`, `.ascii`, `.asciz`, `.string`, `.zero`, `.space` 등) — 상수 문자열이나 전역 변수의 값이라서.
    단, 같은 섹션에서 바로 앞의 레이블을 숨겼으면 함께 숨깁니다
  - 레이블 없이 변수를 정의하는 `.comm`, `.lcomm`, `.zerofill`, `.tbss`
- **반복 계산**: 데이터가 다른 데이터의 레이블을 가리킬 수 있어서(예: 문자열 포인터 표 `names.rel`이 `.L.str`을 가리킴),
  남긴 데이터가 쓰는 이름을 모으고 → 그 이름의 레이블과 데이터를 남기는 일을 더 늘지 않을 때까지 반복합니다
- **시간**: `HashMap`을 쓰는 Rust 프로그램의 `opt-level=0`(15,839줄 → 11,180줄)이 약 30ms

clang, GCC, rustc 모두 GNU 어셈블러 문법으로 내놓으므로 컴파일러별 분기 없이 하나의 규칙으로 macOS(Mach-O), Linux(ELF),
Windows(COFF, MSVC/MinGW) 출력을 다룹니다. 테스트에는 각 형식의 실제 출력을 줄여서 넣었습니다.

---

## `languages.js`
**파일**: [`frontend/src/languages.js`](../frontend/src/languages.js)

언어 선택과 언어별 코드 초안을 다루는 순수 함수 모음. 화면 상태와 분리해서 `languages.test.js`로 테스트합니다.

### 상수
- `SAMPLE_CODE`: 언어 id(`c`, `cpp`, `rust`)별 예제 코드. 언어를 처음 고르거나 "예제로 되돌리기"를 누르면 들어갑니다.
  C/C++은 표준 헤더를 쓰지 않는 예제만 둡니다 — Windows에 LLVM만 설치한 환경에서는 표준 헤더를 못 찾아 첫 화면부터 실패하기 때문.
  Rust 예제는 `fn main` 없이 `pub fn`을 씁니다 — 라이브러리로 컴파일되어 `pub` 함수만 어셈블리에 남기 때문
- `INITIAL_LANGUAGE`: 처음 보여줄 언어 (`'c'`)
- `FALLBACK_COMPILERS`: `/api/compilers`를 받지 못했을 때 쓰는 목록. 서버의 기본 컴파일러인 Clang 하나만 둡니다

### `groupByLanguage(compilers) : Array<{ id, label, available, compilers }>`
`/api/compilers` 응답을 `language` 기준으로 묶습니다. 언어 순서와 언어 안의 컴파일러 순서는 서버가 준 순서
(= `application.yml`에 적은 순서)를 그대로 따릅니다. 언어의 `available`은 사용 가능한 컴파일러가 하나라도 있는지입니다.

### `defaultCompilerFor(language)` / `initialLanguage(languages)`
- `defaultCompilerFor`: 그 언어에서 기본으로 고를 컴파일러 — 사용 가능한 첫 컴파일러, 없으면 첫 컴파일러
- `initialLanguage`: 페이지를 열었을 때 고를 언어 — 사용 가능한 컴파일러가 있는 첫 언어, 없으면 첫 언어

### `switchDraft(drafts, fromLanguage, toLanguage, currentCode) : { drafts, code }`
언어를 바꿀 때 지금 에디터의 코드를 `drafts[fromLanguage]`에 보관하고, `drafts[toLanguage]`(없으면 그 언어의
`SAMPLE_CODE`, 그것도 없으면 빈 문자열)를 에디터에 넣을 코드로 돌려줍니다. 원래 객체는 바꾸지 않고 새 객체를 돌려줍니다.

---

## `remarks.js`
**파일**: [`frontend/src/remarks.js`](../frontend/src/remarks.js)

서버가 준 최적화 remark(`result.remarks`)를 화면에 보여주기 위한 순수 함수 모음. `remarks.test.js`로 테스트합니다.
remark를 뽑고 종류를 붙이는 일은 전부 백엔드가 하므로([backend.md의 `RemarkFormat`](./backend.md#remarkformat-enum)),
여기서는 세고 고르기만 합니다.

- `REMARK_KIND_LABEL`: `kind`를 화면 표기로 바꾸는 표 (`APPLIED` → "적용", `MISSED` → "놓침")
- `summarizeRemarks(remarks) : { applied, missed }`: 적용된 것과 놓친 것의 건수. 서버가 하나로 합친 remark는
  `count`와 상관없이 한 건으로 셈
- `remarkTopic(remark) : string | null`: remark 앞에 붙일 종류 이름. `topicLabel`(한국어 이름)이 있으면 그것을,
  없으면 `pass`(컴파일러의 패스 이름)를, 둘 다 없으면(GCC의 일부 remark) `null`

---

## `App.jsx`
**파일**: [`frontend/src/App.jsx`](../frontend/src/App.jsx)

### 상수
- `BACKEND_UNREACHABLE`: 서버에 연결하지 못했을 때 보여주는 안내 문구
- 백엔드 주소는 상수로 두지 않습니다. API는 항상 화면을 내려준 서버의 상대 경로(`/api/compilers`,
  `/api/compile`)로 호출합니다 — 배포된 jar에서는 백엔드가 화면도 함께 내보내고, 개발 중에는 Vite 개발 서버가
  `/api`를 백엔드로 넘겨주기 때문입니다 (아래 `vite.config.js` 참고).
- 최적화 레벨의 이름과 설명은 프론트엔드에 두지 않습니다. 백엔드가 결과마다 `levelLabel`, `levelDescription`으로
  보내주므로, 레벨이 다른 언어가 추가돼도 화면을 고칠 필요가 없습니다.

### `InfoTooltip({ text, align })` (컴포넌트)
컬럼 헤더의 ⓘ 아이콘. 마우스 호버 또는 `tabIndex={0}`을 통한 키보드 포커스 시
`text`를 말풍선(`tooltip-bubble`)으로 보여준다. CSS의 `:hover`/`:focus` 선택자로만 동작하며
별도 JS 상태(useState) 없이 구현됨.
- 말풍선은 기본으로 아이콘의 왼쪽 끝에서 오른쪽으로 펼쳐진다
- `align="end"`면 아이콘의 오른쪽 끝에 맞춰 왼쪽으로 펼친다(`tooltip-bubble-end`). 화면 오른쪽 끝에 놓이는 "지시어 숨기기"의 아이콘이 이렇게 써서,
  말풍선이 화면 밖으로 나가 가로 스크롤이 생기지 않게 한다

### `formatBytes(bytes) : string`
바이트 숫자를 사람이 읽기 좋은 문자열로 변환.
- `null`이면 `'-'`
- 1024 미만이면 `"712 B"`처럼 그대로
- 그 이상이면 `"0.6 KB"`처럼 소수점 1자리 KB로 변환

### `formatMs(ms) : string`
밀리초 숫자(`OptimizationResult.executionTimeMs`)를 사람이 읽기 좋은 문자열로 변환.
- `null`이면 `'-'`
- 1ms 미만이면 마이크로초로 바꿔 `"420µs"`처럼 표시 (짧은 함수는 실행 시간이 1ms를 안 넘는 경우가 많아서)
- 그 이상이면 `"3.42ms"`처럼 소수점 2자리 ms로 표시

### `CodeEditor({ value, onChange })` (컴포넌트)
화면 위쪽의 코드 입력창. 외부 편집기 라이브러리 없이 `<textarea>` 왼쪽에 줄 번호 칸(`code-gutter`)을 붙였습니다.
최적화 기록의 `7행`이나 컴파일 에러의 `input.c:7:3`이 어느 줄인지 바로 찾게 하려는 것입니다.
- 줄 번호는 `value`를 `\n`으로 나눈 줄 수만큼 `1\n2\n3…`을 `<pre>` 하나에 넣어 그립니다. `useMemo`로 줄 수가 바뀔 때만 다시 만듭니다
- 긴 줄을 접어서 보여주면 한 줄이 여러 줄을 차지해 번호와 어긋나므로, `wrap="off"`로 접지 않고 입력창 안에서 가로로 스크롤합니다
- 번호 칸은 스스로 스크롤하지 않습니다. 입력창의 `scroll` 이벤트마다 번호 목록을 `translateY(-scrollTop)`으로 같은 만큼 밀어 올립니다.
  번호 칸에 따로 스크롤을 주면 입력창 아래의 가로 스크롤바(Windows처럼 스크롤바가 자리를 차지하는 환경) 높이만큼 맨 끝에서 어긋나기 때문입니다.
  `value`가 바뀔 때도(`useLayoutEffect`) 한 번 더 맞춥니다 — "예제로 되돌리기"나 언어 변경으로 코드가 짧아지면 브라우저가 스크롤 위치를 줄이는데,
  이때 `scroll` 이벤트가 온다는 보장이 없어서입니다
- 번호 목록은 `position: absolute`라서 줄이 많아도 상자를 늘리지 않습니다. 상자 높이는 입력창(최소 220px, 세로로 끌어서 조절)을 따르고,
  번호 칸의 너비는 가장 큰 번호의 자릿수(최소 2자리)를 CSS 변수 `--line-digits`로 넘겨 `ch` 단위로 정합니다
- 두 칸의 글꼴·크기·줄 높이·위쪽 여백은 바깥 상자(`.code-editor`)에서 물려받아 항상 같습니다. 테두리와 배경도 바깥 상자에 있고,
  입력창에 포커스가 가면 상자 전체의 테두리가 강조색으로 바뀝니다(`:focus-within`)
- 번호 칸은 `aria-hidden`이고 마우스로 선택되지 않습니다(`user-select: none`). 입력창에는 `aria-label="소스 코드"`를 붙였습니다
- 바꾼 코드는 `onChange(code)`로 올려보냅니다 (`App`의 `setCode`)

### `AssemblyView({ assembly, lineStates })` (컴포넌트)
어셈블리 텍스트를 줄 단위 `<div>`로 쪼개서 렌더링. `lineStates`는 `diffLines()`가 돌려준 줄별 상태입니다.
- `lineStates`가 없으면(= 기준 레벨 자신이거나, 기준 레벨이 컴파일에 실패했거나, 비교를 건너뛴 경우) 그냥 평문으로 표시
- 있으면 `'added'`인 줄에 `diff-added` CSS 클래스를 붙여 초록색으로 강조

### `BaseLevelSelector({ results, base, onSelect })` (컴포넌트)
결과 위에 놓이는 "비교 기준" 버튼 그룹. `results`의 레벨마다 `levelLabel`을 단 버튼을 하나씩 그립니다
(레벨 목록을 프론트엔드에 두지 않으므로 언어마다 레벨이 달라도 그대로 동작).
- 지금 `base`인 레벨의 버튼은 채워진 색(`base-selector-btn-active`)과 `aria-pressed=true`로 표시
- 컴파일에 실패한 레벨은 비교할 어셈블리가 없으므로 버튼을 `disabled`로 막음
- 누르면 `onSelect(level)`로 그 레벨 id를 올려보냄 (`App`의 `setBaseLevel`)

### `DirectiveFilterToggle({ checked, onChange })` (컴포넌트)
"비교 기준" 버튼 줄의 오른쪽 끝에 놓이는 "지시어 숨기기" 체크박스와 설명 툴팁(`align="end"`).
좁은 화면에서 다음 줄로 넘어가도 `margin-left: auto`로 오른쪽 끝에 붙어 있습니다.
바꾸면 `onChange(checked)`로 올려보냅니다 (`App`의 `setHideDirectives`). 다시 컴파일하지 않고 화면에서만 바뀝니다.
"실행 시간 측정" 체크박스와 같은 `option-toggle` 스타일을 씁니다.

### `RemarkPanel({ remarks, omittedCount })` (컴포넌트)
컬럼 안에서 열 제목과 어셈블리 사이에 놓이는 "최적화 기록" 영역. 컴파일러가 그 레벨에서 남긴 remark를 보여줍니다.
- `remarks`가 `null`이면(remark를 내지 않는 rustc) 아무것도 그리지 않음
- `<details open>`이라 처음에는 펼쳐져 있고, 제목 줄을 누르면 접힘. 제목 줄에는 `summarizeRemarks()`의 "적용 N · 놓침 M"을 표시
- 항목마다 초록색 "적용" / 노란색 "놓침", 소스 줄 번호(`7행`), 종류 이름(`remarkTopic()`), 컴파일러의 원문 메시지를 표시.
  `topicDescription`이 있으면 종류 이름에 점선 밑줄을 긋고 `title`로 설명을 붙임. `count`가 2 이상이면 메시지 뒤에 `×2`
- 목록은 높이를 제한하고 안에서 스크롤함 (레벨마다 건수가 달라도 어셈블리가 너무 아래로 밀리지 않게)
- `remarks`가 빈 배열이면 "이 레벨에서 컴파일러가 남긴 기록이 없습니다.", `omittedCount`가 있으면 생략한 건수를 표시

### `OptimizationColumn({ result, base })` (컴포넌트)
결과 화면의 컬럼 하나(예: "-O2" 박스)를 그리는 컴포넌트. `base`는 비교 기준이 되는 결과입니다
(기본은 응답의 첫 번째 레벨, `BaseLevelSelector`로 고르면 그 레벨).
`result`와 `base`는 `App`의 `shownResults`에서 오므로, "지시어 숨기기"가 켜져 있으면 `assembly`가 이미 걸러진 텍스트입니다.
`memo`로 감싸서 결과나 기준이 바뀔 때만 다시 그립니다 (에디터에 글자를 칠 때마다 `App`이 다시 그려지기 때문).
기준과의 비교는 `useDiffSummary(baseAssembly, assembly)` 훅이 `runDiff()`로 worker에 맡깁니다. 두 어셈블리가 바뀌면 이전 비교를 취소하고 새로 시작하며,
결과가 오기 전에는 `undefined`, 비교를 건너뛰었거나 비교하지 않으면 `null`을 돌려줍니다.
- 헤더에 `result.levelLabel`(예: `-O0`) + `InfoTooltip(result.levelDescription)`, 바이너리(오브젝트) 크기, 컴파일 시간(ms) 표시
- 기준 레벨 자신이면 헤더에 "기준" 배지를 붙이고 테두리를 강조색(`opt-column-base`)으로 표시
- 기준 레벨이 아니고 기준과 자신 모두 컴파일에 성공했으면 `diffLines()`로 `(+added / -removed vs {base.levelLabel})` 배지를 헤더에 추가.
  결과를 기다리는 동안에는 회색 "(비교 중…)"(`diff-pending`)을 표시하고 어셈블리는 강조 없이 먼저 보여줌.
  `diffLines()`가 `null`을 돌려주면 대신 회색 "(줄이 너무 많아 비교 생략)"을 표시
- `result.executionTimeMs`가 있으면(= `runBenchmark=true`였고 측정 성공) `⏱ 실행 {formatMs(...)}` 배지 추가
- 측정을 시도했지만 실패했으면(주로 `main()` 없음, Rust는 패닉으로 끝난 경우도 포함) `executionError`를 툴팁으로 붙인
  회색 "⏱ 실행 불가" 배지를 대신 표시 — 컴파일 자체는 성공이므로 에러로 취급하지 않음
- `result.notice`가 있으면 어셈블리 위에 노란색 안내 문구를 표시 (예: Rust에서 어셈블리에 함수가 없는 이유)
- 컴파일에 성공했으면 어셈블리 위에 `RemarkPanel`(`result.remarks`, `result.omittedRemarkCount`)을 표시
- 컴파일 실패 시 헤더에 빨간 "컴파일 실패" 배지를 띄우고, 본문에는
  `"⚠ {compilerLabel}가 이 코드를 컴파일하지 못했습니다"` 안내와 함께 `errorMessage`(정리된 컴파일러 stderr)를 표시

### `App()` (최상위 컴포넌트)
페이지 전체를 구성하는 루트 컴포넌트. 관리하는 상태:
- `code`: 코드 에디터(`CodeEditor`)에 입력된 코드 (처음에는 `SAMPLE_CODE.c`)
- `drafts`: 언어 id별로 편집하던 코드. 다른 언어에 갔다가 돌아오면 여기서 복원 (메모리에만 있어서 새로고침하면 사라짐)
- `results`: 백엔드 응답(`OptimizationResult` 배열), 아직 요청 전이면 `null`
- `baseLevel`: "비교 기준"으로 고른 레벨 id. `null`이면 첫 번째 결과를 기준으로 씀.
  렌더링할 때 `shownResults`에서 이 id를 찾고, 없으면 `shownResults[0]`을 기준(`base`)으로 삼음
- `hideDirectives`: "지시어 숨기기" 체크박스 상태 (기본 `true`). 다시 비교해도 유지됨
- `loading`: 요청 진행 중 여부 (버튼 비활성화 + "컴파일 중..." 텍스트에 사용)
- `error`: 요청 실패 시 에러 메시지
- `compilers`: `GET /api/compilers` 응답(`{id, label, available, language, languageLabel}` 배열). 받기 전이나 실패 시 `FALLBACK_COMPILERS`
- `language`: 선택된 언어 id (기본 `'c'`)
- `selectedCompiler`: 선택된 컴파일러 id (기본 `'clang'`)
- `runBenchmark`: "실행 시간 측정" 체크박스 상태 (기본 `false`, 옵트인)

매 렌더링마다 `groupByLanguage(compilers)`로 언어 목록을 만들고, 그중 `language`에 해당하는 것을 현재 언어로 씁니다.

화면에 그리는 결과는 `results`가 아니라 `shownResults`입니다. `hideDirectives`가 켜져 있으면 컴파일에 성공한 결과마다
`assembly`를 `filterAssembly()`로 걸러낸 사본을 만들고, 꺼져 있으면 `results`를 그대로 씁니다.
`useMemo`로 `results`나 `hideDirectives`가 바뀔 때만 새로 만듭니다 — 렌더링마다 새 객체를 만들면 `OptimizationColumn`의 `memo`가
소용없어져서 에디터에 글자를 칠 때마다 diff를 다시 계산하게 되기 때문입니다.

#### `useEffect` (마운트 시 1회)
`GET /api/compilers`를 호출해 `compilers`를 채우고, `initialLanguage()`로 고른 언어와 그 언어의
`defaultCompilerFor()` 컴파일러를 선택한다. C 컴파일러가 하나도 없어서 다른 언어로 시작하면 그 언어의 예제를 넣는다.
요청이 실패하거나 빈 목록이면 `FALLBACK_COMPILERS`(Clang 하나)를 그대로 둬서 화면이 깨지지 않게 한다.

#### `handleLanguageChange(nextLanguage)` (내부 함수, 언어 드롭다운의 onChange)
1. `switchDraft()`로 지금 코드를 원래 언어의 초안에 보관하고, 새 언어의 초안(없으면 예제)을 에디터에 넣음
2. 새 언어의 `defaultCompilerFor()` 컴파일러를 선택
3. `results`와 `error`를 지움 — 이전 언어의 결과가 남아 있으면 에디터의 코드와 맞지 않아 헷갈리기 때문
   (컴파일러만 바꿀 때는 코드도 결과도 그대로 둠)

#### `handleCompare()` (내부 함수, "비교하기" 버튼의 onClick)
1. `loading = true`, 이전 `results`/`error` 초기화, `baseLevel = null`
   (이전에 고른 기준 레벨이 새 결과에서는 컴파일에 실패했을 수도 있어서 첫 레벨로 되돌림)
2. `POST /api/compile`로 `{ code, compiler: selectedCompiler, runBenchmark }`를 JSON으로 전송 (언어는 보내지 않음 — 서버가 컴파일러로 정함)
3. 응답이 실패(`!res.ok`)면 에러 바디(`{ message }`, 백엔드의 `GlobalExceptionHandler`가 내려줌)를 파싱해
   메시지를 뽑고, 없으면 HTTP 상태코드로 대체 메시지 생성
   - 상태코드가 502/503/504면 `BACKEND_UNREACHABLE` 문구를 씀. 개발 서버의 프록시는 백엔드가 꺼져 있으면 502를 돌려줌
4. 성공하면 `setResults(data)`
5. `fetch` 자체가 실패(`TypeError`)하면 서버에 연결하지 못한 것이므로 `BACKEND_UNREACHABLE` 문구를 표시
   (jar로 실행하다 서버를 끈 경우)
6. `finally`에서 `loading = false` (성공/실패 모두)

#### 렌더링 흐름
1. 상단에 로고(`assets/logo-horizontal-dark.svg`)/설명, 줄 번호가 붙은 코드 에디터(`CodeEditor`)
2. 컨트롤 줄
   - **언어** `<select>`: 사용 가능한 컴파일러가 없는 언어는 `disabled` + "(서버에 설치되지 않음)"
   - **컴파일러** `<select>`: 지금 언어의 컴파일러만 표시, 사용 불가능한 항목은 `disabled`
   - "실행 시간 측정" 체크박스(+ 설명 툴팁)
   - **예제로 되돌리기** 버튼: 지금 언어의 `SAMPLE_CODE`로 되돌림. 이미 예제와 같으면 비활성화
3. "비교하기" 버튼, 에러 메시지(있으면)
4. `results`가 있으면:
   - 어떤 컴파일러 기준 결과인지 안내 문구(`results[0].compilerLabel`) 표시
   - `BaseLevelSelector`("비교 기준" 버튼 그룹)와 `DirectiveFilterToggle`("지시어 숨기기")을 한 줄(`results-toolbar`)에 배치
   - `remarks`가 있는 결과가 하나라도 있으면 "최적화 기록"의 적용/놓침/줄 번호가 무엇인지 한 줄 안내
   - `base`(기본은 `shownResults[0]`, clang/gcc에서는 `-O0`)를 기준으로 삼아 `shownResults`의 각 결과를 `OptimizationColumn`으로 렌더링

---

## `vite.config.js`
**파일**: [`frontend/vite.config.js`](../frontend/vite.config.js)

- `server.proxy`: 개발 서버(5173)로 온 `/api` 요청을 백엔드(`http://127.0.0.1:8080`)로 넘깁니다.
  덕분에 `App.jsx`는 개발 중에도, 배포된 jar에서도 똑같이 `/api/...`만 호출하면 됩니다.
  - `localhost`가 아니라 `127.0.0.1`을 쓰는 이유: 백엔드는 `127.0.0.1`에서만 듣는데, 환경에 따라
    `localhost`가 IPv6 주소(`::1`)로 먼저 풀려 연결이 거부될 수 있기 때문입니다.
- `npm run build`의 결과물(`frontend/dist`)은 백엔드의 `bundle` Maven 프로필이 jar 안에 넣습니다
  ([backend.md](./backend.md#bundle-프로필--실행-가능한-jar-하나-만들기) 참고).

---

## `main.jsx`
**파일**: [`frontend/src/main.jsx`](../frontend/src/main.jsx)

Vite 기본 진입점. `#root` DOM에 `<App />`을 `ReactDOM.createRoot()`로 마운트합니다. (Vite 템플릿 기본 생성 파일, 프로젝트 고유 로직 없음)
