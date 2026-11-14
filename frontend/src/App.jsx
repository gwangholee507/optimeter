import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import './App.css'
import logo from './assets/logo-horizontal-dark.svg'
import { filterAssembly } from './assemblyFilter'
import { runDiff } from './diffRunner'
import {
  FALLBACK_COMPILERS,
  INITIAL_LANGUAGE,
  SAMPLE_CODE,
  defaultCompilerFor,
  groupByLanguage,
  initialLanguage,
  switchDraft,
} from './languages'
import { REMARK_KIND_LABEL, remarkTopic, summarizeRemarks } from './remarks'

const BACKEND_UNREACHABLE = '백엔드 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인해주세요.'

function formatBytes(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

function formatMs(ms) {
  if (ms == null) return '-'
  if (ms < 1) return `${(ms * 1000).toFixed(0)}µs`
  return `${ms.toFixed(2)}ms`
}

// align="end"면 말풍선을 아이콘의 오른쪽 끝에 맞춰 왼쪽으로 펼친다 (화면 오른쪽 끝에 있는 아이콘용)
function InfoTooltip({ text, align }) {
  return (
    <span className="info-tooltip" tabIndex={0}>
      <span className="info-icon" aria-hidden="true">
        ⓘ
      </span>
      <span
        className={align === 'end' ? 'tooltip-bubble tooltip-bubble-end' : 'tooltip-bubble'}
        role="tooltip"
      >
        {text}
      </span>
    </span>
  )
}

// 왼쪽에 줄 번호를 붙인 코드 입력창. 최적화 기록의 "7행"이나 컴파일 에러의 "input.c:7:3"이 어느 줄인지 바로 찾을 수 있게 한다.
// 긴 줄을 접어서 보여주면 번호와 줄이 어긋나므로 접지 않고(wrap="off") 가로로 스크롤한다.
function CodeEditor({ value, onChange }) {
  const textareaRef = useRef(null)
  const numbersRef = useRef(null)
  const lineCount = value.split('\n').length
  // 글자를 칠 때마다가 아니라 줄 수가 바뀔 때만 번호를 다시 만든다
  const lineNumbers = useMemo(
    () => Array.from({ length: lineCount }, (_, i) => i + 1).join('\n'),
    [lineCount],
  )

  // 번호 칸은 스스로 스크롤하지 않고, 입력창이 스크롤한 만큼 위로 밀어 올린다.
  // 번호 칸에 따로 스크롤을 주면 입력창 아래에 생기는 가로 스크롤바 높이만큼 맨 끝에서 어긋난다
  function syncScroll() {
    numbersRef.current.style.transform = `translateY(${-textareaRef.current.scrollTop}px)`
  }

  // 언어를 바꾸거나 예제로 되돌려서 코드가 짧아지면 브라우저가 스크롤 위치를 줄이는데, 이때는 scroll 이벤트가 오지 않을 수 있다
  useLayoutEffect(() => {
    syncScroll()
  }, [value])

  return (
    // 번호 칸의 너비는 가장 큰 번호의 자릿수(최소 2자리)에 맞춘다
    <div className="code-editor" style={{ '--line-digits': Math.max(2, String(lineCount).length) }}>
      <div className="code-gutter" aria-hidden="true">
        <pre ref={numbersRef}>{lineNumbers}</pre>
      </div>
      <textarea
        ref={textareaRef}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onScroll={syncScroll}
        wrap="off"
        spellCheck={false}
        aria-label="소스 코드"
      />
    </div>
  )
}

// lineStates: diffLines가 돌려준 줄별 상태. 기준 레벨이거나 비교를 건너뛰었으면 없어서 평문으로 표시된다
function AssemblyView({ assembly, lineStates }) {
  return (
    <pre className="asm-block">
      {assembly.split('\n').map((line, idx) => (
        <div
          key={idx}
          className={lineStates?.[idx] === 'added' ? 'asm-line diff-added' : 'asm-line'}
        >
          {line}
        </div>
      ))}
    </pre>
  )
}

// 결과 위에 레벨 버튼을 늘어놓고, 누른 레벨을 diff의 비교 기준으로 삼는다.
// 컴파일에 실패한 레벨은 비교할 어셈블리가 없으므로 고를 수 없다.
function BaseLevelSelector({ results, base, onSelect }) {
  return (
    <div className="base-selector" role="group" aria-label="비교 기준">
      <span className="base-selector-label">
        비교 기준
        <InfoTooltip text="초록색 강조와 (+추가 / -삭제) 배지를 어느 레벨과 비교해서 계산할지 고릅니다. 예를 들어 바로 아래 레벨을 고르면 그 레벨보다 무엇을 더 바꿨는지 볼 수 있습니다." />
      </span>
      {results.map((r) => (
        <button
          key={r.level}
          type="button"
          className={
            r.level === base.level ? 'base-selector-btn base-selector-btn-active' : 'base-selector-btn'
          }
          aria-pressed={r.level === base.level}
          disabled={!r.success}
          title={r.success ? undefined : '컴파일에 실패한 레벨은 기준으로 고를 수 없습니다'}
          onClick={() => onSelect(r.level)}
        >
          {r.levelLabel}
        </button>
      ))}
    </div>
  )
}

// 컴파일러가 이 레벨에서 남긴 최적화 remark. 어셈블리 위에 접을 수 있는 목록으로 보여준다.
// remarks가 null이면 remark를 내지 않는 컴파일러(rustc)라서 아무것도 그리지 않는다.
function RemarkPanel({ remarks, omittedCount }) {
  if (remarks == null) return null
  const { applied, missed } = summarizeRemarks(remarks)

  return (
    <details className="remark-panel" open>
      <summary>
        최적화 기록
        <span className="remark-count remark-applied">적용 {applied}</span>
        <span className="remark-count remark-missed">놓침 {missed}</span>
      </summary>
      {remarks.length === 0 ? (
        <p className="remark-empty">이 레벨에서 컴파일러가 남긴 기록이 없습니다.</p>
      ) : (
        <ul className="remark-list">
          {remarks.map((remark, idx) => {
            const topic = remarkTopic(remark)
            return (
              <li key={idx} className="remark-item">
                <span
                  className={
                    remark.kind === 'APPLIED' ? 'remark-kind remark-applied' : 'remark-kind remark-missed'
                  }
                >
                  {REMARK_KIND_LABEL[remark.kind] ?? remark.kind}
                </span>
                <span className="remark-line">{remark.line}행</span>
                {topic && (
                  <span
                    className={remark.topicDescription ? 'remark-topic remark-topic-known' : 'remark-topic'}
                    title={remark.topicDescription ?? undefined}
                  >
                    {topic}
                  </span>
                )}
                <span className="remark-message">
                  {remark.message}
                  {remark.count > 1 && <span className="remark-repeat"> ×{remark.count}</span>}
                </span>
              </li>
            )
          })}
        </ul>
      )}
      {omittedCount > 0 && (
        <p className="remark-empty">너무 많아서 뒤쪽 {omittedCount}건은 생략했습니다.</p>
      )}
    </details>
  )
}

// 켜져 있으면 어셈블리에서 지시어처럼 코드가 아닌 줄을 숨긴다. 다시 컴파일하지 않고 화면에서만 바뀐다.
function DirectiveFilterToggle({ checked, onChange }) {
  return (
    <label className="option-toggle">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      지시어 숨기기
      <InfoTooltip
        align="end"
        text="어셈블러에게 주는 지시(.cfi_startproc, .p2align, .section 같은 줄), 주석만 있는 줄, 그런 지시에서만 쓰는 내부 레이블, 디버그 정보와 예외 처리 표를 숨기고 실제로 실행되는 명령어와 데이터만 보여줍니다. 바뀐 줄 강조와 (+추가 / -삭제) 배지도 남은 줄만으로 계산합니다. 오브젝트 크기는 그대로입니다." />
    </label>
  )
}

// 기준 어셈블리와의 비교(diffLines)를 worker에서 계산한다. 어셈블리가 수천 줄이면 비교에 수백 ms가 걸려서
// 화면 스레드에서 하면 그동안 화면이 멈춘다. 두 어셈블리 중 하나라도 없으면 비교하지 않는다.
// 돌려주는 값: 결과를 기다리는 중이면 undefined, 줄이 너무 많아 비교를 건너뛰었거나 비교하지 않으면 null.
function useDiffSummary(baseAssembly, assembly) {
  const [finished, setFinished] = useState(null)
  const comparing = baseAssembly != null && assembly != null

  useEffect(() => {
    if (!comparing) return undefined
    const job = runDiff(baseAssembly, assembly)
    job.promise.then((summary) => setFinished({ baseAssembly, assembly, summary }))
    return job.cancel
  }, [comparing, baseAssembly, assembly])

  if (!comparing) return null
  // 입력이 바뀌었는데 이전 입력의 결과가 남아 있으면 아직 기다리는 중이다
  return finished?.baseAssembly === baseAssembly && finished?.assembly === assembly ? finished.summary : undefined
}

// base: 비교 기준이 되는 결과 (기본값은 서버가 준 첫 번째 레벨, clang/gcc에서는 -O0)
// 에디터에 글자를 칠 때마다 App이 다시 그려지므로 memo로 결과가 바뀔 때만 다시 그린다.
const OptimizationColumn = memo(function OptimizationColumn({ result, base }) {
  const isBaseline = result.level === base.level
  const baseAssembly = !isBaseline && base.success ? base.assembly : null
  const comparing = result.success && baseAssembly != null
  const diffSummary = useDiffSummary(comparing ? baseAssembly : null, comparing ? result.assembly : null)

  return (
    <div className={isBaseline ? 'opt-column opt-column-base' : 'opt-column'}>
      <div className="opt-header">
        <h3>
          {result.levelLabel}
          <InfoTooltip text={result.levelDescription} />
          {isBaseline && <span className="base-badge">기준</span>}
        </h3>
        {result.success ? (
          <span className="opt-meta">
            {formatBytes(result.binarySizeBytes)} · {result.compileTimeMs}ms
            {diffSummary && (
              <span className="diff-badge">
                {' '}
                (+{diffSummary.addedCount} / -{diffSummary.removedCount} vs {base.levelLabel})
              </span>
            )}
            {comparing && diffSummary === undefined && (
              <span className="exec-badge-muted diff-pending"> (비교 중…)</span>
            )}
            {comparing && diffSummary === null && (
              <span className="exec-badge-muted"> (줄이 너무 많아 비교 생략)</span>
            )}
            {result.executionTimeMs != null && (
              <span className="exec-badge"> · ⏱ 실행 {formatMs(result.executionTimeMs)}</span>
            )}
            {result.executionTimeMs == null && result.executionError && (
              <span className="exec-badge exec-badge-muted" title={result.executionError}>
                {' '}
                · ⏱ 실행 불가
              </span>
            )}
          </span>
        ) : (
          <span className="opt-meta error-badge">컴파일 실패</span>
        )}
      </div>
      {result.notice && <p className="result-notice">ⓘ {result.notice}</p>}
      {result.success && (
        <RemarkPanel remarks={result.remarks} omittedCount={result.omittedRemarkCount} />
      )}
      {result.success ? (
        <AssemblyView assembly={result.assembly} lineStates={diffSummary?.lineStates} />
      ) : (
        <div className="error-box">
          <p className="error-box-title">⚠ {result.compilerLabel || '컴파일러'}가 이 코드를 컴파일하지 못했습니다</p>
          <pre className="asm-block error">{result.errorMessage}</pre>
        </div>
      )}
    </div>
  )
})

function App() {
  const [code, setCode] = useState(SAMPLE_CODE[INITIAL_LANGUAGE])
  // 언어별로 편집하던 코드. 다른 언어로 갔다가 돌아오면 여기서 복원한다 (새로고침하면 사라짐)
  const [drafts, setDrafts] = useState({})
  const [results, setResults] = useState(null)
  // 비교 기준으로 고른 레벨 id. null이면 서버가 준 첫 번째 레벨을 기준으로 삼는다
  const [baseLevel, setBaseLevel] = useState(null)
  // 다시 비교해도 유지한다 (사용자가 끈 상태로 계속 보고 싶을 수 있다)
  const [hideDirectives, setHideDirectives] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const [compilers, setCompilers] = useState(FALLBACK_COMPILERS)
  const [language, setLanguage] = useState(INITIAL_LANGUAGE)
  const [selectedCompiler, setSelectedCompiler] = useState(FALLBACK_COMPILERS[0].id)
  const [runBenchmark, setRunBenchmark] = useState(false)

  const languages = groupByLanguage(compilers)
  const currentLanguage = languages.find((l) => l.id === language) ?? languages[0]
  const sampleCode = SAMPLE_CODE[currentLanguage.id] ?? ''
  // 화면과 diff에 쓰는 결과. 지시어를 숨기면 어셈블리만 걸러낸 사본이 된다.
  // 결과나 필터가 바뀔 때만 새로 만들어야 OptimizationColumn의 memo가 글자를 칠 때마다 diff를 다시 하지 않는다.
  const shownResults = useMemo(
    () =>
      hideDirectives && results
        ? results.map((r) => (r.success ? { ...r, assembly: filterAssembly(r.assembly) } : r))
        : results,
    [results, hideDirectives],
  )
  const base = shownResults?.find((r) => r.level === baseLevel) ?? shownResults?.[0]

  useEffect(() => {
    // 목록을 받지 못하면 FALLBACK_COMPILERS를 그대로 둬서 화면이 깨지지 않게 한다
    fetch('/api/compilers')
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        if (data.length === 0) return
        setCompilers(data)
        const first = initialLanguage(groupByLanguage(data))
        setLanguage(first.id)
        setSelectedCompiler(defaultCompilerFor(first).id)
        // C 컴파일러가 하나도 없어서 다른 언어로 시작하면 그 언어의 예제를 넣는다
        if (first.id !== INITIAL_LANGUAGE) setCode(SAMPLE_CODE[first.id] ?? '')
      })
      .catch(() => {})
  }, [])

  function handleLanguageChange(nextLanguage) {
    const next = switchDraft(drafts, currentLanguage.id, nextLanguage, code)
    setDrafts(next.drafts)
    setCode(next.code)
    setLanguage(nextLanguage)
    setSelectedCompiler(defaultCompilerFor(languages.find((l) => l.id === nextLanguage)).id)
    // 이전 언어의 결과가 남아 있으면 에디터의 코드와 맞지 않아 헷갈리므로 지운다
    setResults(null)
    setError(null)
  }

  async function handleCompare() {
    setLoading(true)
    setError(null)
    setResults(null)
    // 새 결과에서는 이전에 고른 레벨이 실패했을 수도 있으므로 기준을 첫 레벨로 되돌린다
    setBaseLevel(null)
    try {
      const res = await fetch('/api/compile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, compiler: selectedCompiler, runBenchmark }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        // 개발 서버(Vite)의 프록시는 백엔드가 꺼져 있으면 502를 돌려준다
        const fallback = [502, 503, 504].includes(res.status)
          ? BACKEND_UNREACHABLE
          : `요청 실패 (HTTP ${res.status})`
        throw new Error(body?.message || fallback)
      }
      const data = await res.json()
      setResults(data)
    } catch (e) {
      // 서버에 아예 연결하지 못하면 fetch가 TypeError를 던진다 (jar로 실행하다 서버를 끈 경우)
      setError(e instanceof TypeError ? BACKEND_UNREACHABLE : e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="page">
      <header>
        <h1 className="logo">
          <img src={logo} alt="Optimeter" />
        </h1>
        <p className="subtitle">
          최적화 레벨이 코드를 어떻게 바꾸는지 눈으로 보고, 숫자로 잽니다.
        </p>
      </header>

      <section className="editor-section">
        <CodeEditor value={code} onChange={setCode} />

        <div className="controls-row">
          <label className="select-field">
            언어
            <select
              value={currentLanguage.id}
              onChange={(e) => handleLanguageChange(e.target.value)}
            >
              {languages.map((l) => (
                <option key={l.id} value={l.id} disabled={!l.available}>
                  {l.label}
                  {!l.available ? ' (서버에 설치되지 않음)' : ''}
                </option>
              ))}
            </select>
          </label>

          <label className="select-field">
            컴파일러
            <select
              value={selectedCompiler}
              onChange={(e) => setSelectedCompiler(e.target.value)}
            >
              {currentLanguage.compilers.map((c) => (
                <option key={c.id} value={c.id} disabled={!c.available}>
                  {c.label}
                  {!c.available ? ' (서버에 설치되지 않음)' : ''}
                </option>
              ))}
            </select>
          </label>

          <label className="option-toggle">
            <input
              type="checkbox"
              checked={runBenchmark}
              onChange={(e) => setRunBenchmark(e.target.checked)}
            />
            실행 시간 측정
            <InfoTooltip text="최적화 레벨마다 실제로 링크하고 서버에서 여러 번 실행해서 걸린 시간을 잽니다. main() 함수가 있는 코드에서만 동작하며, 서버가 사용자 코드를 직접 실행하는 기능이라 기본값은 꺼져 있습니다." />
          </label>

          <button
            type="button"
            className="reset-sample"
            onClick={() => setCode(sampleCode)}
            disabled={code === sampleCode}
          >
            예제로 되돌리기
          </button>
        </div>

        <button onClick={handleCompare} disabled={loading}>
          {loading ? '컴파일 중...' : '비교하기'}
        </button>
        {error && <p className="error-message">⚠ {error}</p>}
      </section>

      {shownResults && shownResults.length > 0 && (
        <section className="results-section-wrap">
          <p className="results-meta">
            {shownResults[0].compilerLabel || selectedCompiler} 기준 비교 결과
          </p>
          <div className="results-toolbar">
            <BaseLevelSelector results={shownResults} base={base} onSelect={setBaseLevel} />
            <DirectiveFilterToggle checked={hideDirectives} onChange={setHideDirectives} />
          </div>
          {shownResults.some((r) => r.remarks != null) && (
            <p className="remark-help">
              열마다 있는 <strong>최적화 기록</strong>은 컴파일러가 그 레벨에서 직접 남긴 것입니다.{' '}
              <span className="remark-applied">적용</span>은 실제로 한 최적화,{' '}
              <span className="remark-missed">놓침</span>은 하려다 못 한 최적화이고, 앞의 숫자는 위 코드 입력창 왼쪽에
              표시된 줄 번호입니다.
            </p>
          )}
          <div className="results-section">
            {shownResults.map((r) => (
              <OptimizationColumn key={r.level} result={r} base={base} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

export default App
