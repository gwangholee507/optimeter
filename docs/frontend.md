# 프론트엔드 (React + Vite)

## `diff.js`
**파일**: [`frontend/src/diff.js`](../frontend/src/diff.js)

외부 라이브러리 없이 직접 구현한 줄 단위 diff 유틸리티.

### `diffLines(base, target) : { lineStates, addedCount, removedCount }`
- **입력**: `base`(기준이 되는 줄 배열, 보통 O0 어셈블리), `target`(비교 대상 줄 배열)
- **알고리즘**: LCS(최장 공통 부분 수열)를 DP 테이블(`dp[i][j]`)로 계산한 뒤, 뒤에서부터 되짚어가며(backtrack)
  - `base[i] === target[j]`면 `'same'`
  - 아니고 `base`에서 삭제하는 게 유리하면(`dp[i+1][j] >= dp[i][j+1]`) `removedCount` 증가
  - 그 외에는 `target`에 새로 추가된 줄이므로 `'added'`
- **출력**:
  - `lineStates`: `target`의 각 줄에 대응하는 `'same' | 'added'` 배열 (인덱스가 `target` 줄 번호와 1:1 대응)
  - `addedCount`: `target`에만 있는(새로 생기거나 바뀐) 줄 수
  - `removedCount`: `base`에만 있고 `target`에는 없는(사라진) 줄 수
- **복잡도**: O(n·m) — 어셈블리가 보통 수백 줄 이내라 브라우저에서도 충분히 빠름

---

## `languages.js`
**파일**: [`frontend/src/languages.js`](../frontend/src/languages.js)

언어 선택과 언어별 코드 초안을 다루는 순수 함수 모음. 화면 상태와 분리해서 `languages.test.js`로 테스트합니다.

### 상수
- `SAMPLE_CODE`: 언어 id(`c`, `cpp`)별 예제 코드. 언어를 처음 고르거나 "예제로 되돌리기"를 누르면 들어갑니다.
  표준 헤더를 쓰지 않는 예제만 둡니다 — Windows에 LLVM만 설치한 환경에서는 표준 헤더를 못 찾아 첫 화면부터 실패하기 때문
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

## `App.jsx`
**파일**: [`frontend/src/App.jsx`](../frontend/src/App.jsx)

### 상수
- `BACKEND_UNREACHABLE`: 서버에 연결하지 못했을 때 보여주는 안내 문구
- 백엔드 주소는 상수로 두지 않습니다. API는 항상 화면을 내려준 서버의 상대 경로(`/api/compilers`,
  `/api/compile`)로 호출합니다 — 배포된 jar에서는 백엔드가 화면도 함께 내보내고, 개발 중에는 Vite 개발 서버가
  `/api`를 백엔드로 넘겨주기 때문입니다 (아래 `vite.config.js` 참고).
- 최적화 레벨의 이름과 설명은 프론트엔드에 두지 않습니다. 백엔드가 결과마다 `levelLabel`, `levelDescription`으로
  보내주므로, 레벨이 다른 언어가 추가돼도 화면을 고칠 필요가 없습니다.

### `InfoTooltip({ text })` (컴포넌트)
컬럼 헤더의 ⓘ 아이콘. 마우스 호버 또는 `tabIndex={0}`을 통한 키보드 포커스 시
`text`를 말풍선(`tooltip-bubble`)으로 보여준다. CSS의 `:hover`/`:focus` 선택자로만 동작하며
별도 JS 상태(useState) 없이 구현됨.

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

### `AssemblyView({ assembly, baseAssembly })` (컴포넌트)
어셈블리 텍스트를 줄 단위 `<div>`로 쪼개서 렌더링.
- `baseAssembly`가 없으면(= 기준 레벨 자신이거나 기준 레벨이 컴파일에 실패한 경우) 그냥 평문으로 표시
- `baseAssembly`가 있으면 `diffLines()`로 각 줄이 `'added'`인지 계산해서, 새로 생긴/바뀐 줄에
  `diff-added` CSS 클래스를 붙여 초록색으로 강조

### `OptimizationColumn({ result, base })` (컴포넌트)
결과 화면의 컬럼 하나(예: "-O2" 박스)를 그리는 컴포넌트. `base`는 비교 기준이 되는 결과(응답의 첫 번째 레벨)입니다.
- 헤더에 `result.levelLabel`(예: `-O0`) + `InfoTooltip(result.levelDescription)`, 바이너리(오브젝트) 크기, 컴파일 시간(ms) 표시
- 기준 레벨이 아니고 기준과 자신 모두 컴파일에 성공했으면 `diffLines()`로 `(+added / -removed vs {base.levelLabel})` 배지를 헤더에 추가
- `result.executionTimeMs`가 있으면(= `runBenchmark=true`였고 측정 성공) `⏱ 실행 {formatMs(...)}` 배지 추가
- 측정을 시도했지만 실행 파일을 못 만들었으면(주로 `main()` 없음) `executionError`를 툴팁으로 붙인
  회색 "⏱ 실행 불가" 배지를 대신 표시 — 컴파일 자체는 성공이므로 에러로 취급하지 않음
- 컴파일 실패 시 헤더에 빨간 "컴파일 실패" 배지를 띄우고, 본문에는
  `"⚠ {compilerLabel}가 이 코드를 컴파일하지 못했습니다"` 안내와 함께 `errorMessage`(정리된 컴파일러 stderr)를 표시

### `App()` (최상위 컴포넌트)
페이지 전체를 구성하는 루트 컴포넌트. 관리하는 상태:
- `code`: 텍스트 에디터에 입력된 코드 (처음에는 `SAMPLE_CODE.c`)
- `drafts`: 언어 id별로 편집하던 코드. 다른 언어에 갔다가 돌아오면 여기서 복원 (메모리에만 있어서 새로고침하면 사라짐)
- `results`: 백엔드 응답(`OptimizationResult` 배열), 아직 요청 전이면 `null`
- `loading`: 요청 진행 중 여부 (버튼 비활성화 + "컴파일 중..." 텍스트에 사용)
- `error`: 요청 실패 시 에러 메시지
- `compilers`: `GET /api/compilers` 응답(`{id, label, available, language, languageLabel}` 배열). 받기 전이나 실패 시 `FALLBACK_COMPILERS`
- `language`: 선택된 언어 id (기본 `'c'`)
- `selectedCompiler`: 선택된 컴파일러 id (기본 `'clang'`)
- `runBenchmark`: "실행 시간 측정" 체크박스 상태 (기본 `false`, 옵트인)

매 렌더링마다 `groupByLanguage(compilers)`로 언어 목록을 만들고, 그중 `language`에 해당하는 것을 현재 언어로 씁니다.

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
1. `loading = true`, 이전 `results`/`error` 초기화
2. `POST /api/compile`로 `{ code, compiler: selectedCompiler, runBenchmark }`를 JSON으로 전송 (언어는 보내지 않음 — 서버가 컴파일러로 정함)
3. 응답이 실패(`!res.ok`)면 에러 바디(`{ message }`, 백엔드의 `GlobalExceptionHandler`가 내려줌)를 파싱해
   메시지를 뽑고, 없으면 HTTP 상태코드로 대체 메시지 생성
   - 상태코드가 502/503/504면 `BACKEND_UNREACHABLE` 문구를 씀. 개발 서버의 프록시는 백엔드가 꺼져 있으면 502를 돌려줌
4. 성공하면 `setResults(data)`
5. `fetch` 자체가 실패(`TypeError`)하면 서버에 연결하지 못한 것이므로 `BACKEND_UNREACHABLE` 문구를 표시
   (jar로 실행하다 서버를 끈 경우)
6. `finally`에서 `loading = false` (성공/실패 모두)

#### 렌더링 흐름
1. 상단에 로고(`assets/logo-horizontal-dark.svg`)/설명, 코드 에디터(`<textarea>`)
2. 컨트롤 줄
   - **언어** `<select>`: 사용 가능한 컴파일러가 없는 언어는 `disabled` + "(서버에 설치되지 않음)"
   - **컴파일러** `<select>`: 지금 언어의 컴파일러만 표시, 사용 불가능한 항목은 `disabled`
   - "실행 시간 측정" 체크박스(+ 설명 툴팁)
   - **예제로 되돌리기** 버튼: 지금 언어의 `SAMPLE_CODE`로 되돌림. 이미 예제와 같으면 비활성화
3. "비교하기" 버튼, 에러 메시지(있으면)
4. `results`가 있으면:
   - 어떤 컴파일러 기준 결과인지 안내 문구(`results[0].compilerLabel`) 표시
   - 첫 번째 결과(`results[0]`, clang/gcc에서는 `-O0`)를 기준으로 삼아 각 결과를 `OptimizationColumn`으로 렌더링

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
