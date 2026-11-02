import { memo, useEffect, useMemo, useState } from 'react'
import './App.css'
import logo from './assets/logo-horizontal-dark.svg'
import { diffLines } from './diff'
import {
  FALLBACK_COMPILERS,
  INITIAL_LANGUAGE,
  SAMPLE_CODE,
  defaultCompilerFor,
  groupByLanguage,
  initialLanguage,
  switchDraft,
} from './languages'

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

function InfoTooltip({ text }) {
  return (
    <span className="info-tooltip" tabIndex={0}>
      <span className="info-icon" aria-hidden="true">
        ⓘ
      </span>
      <span className="tooltip-bubble" role="tooltip">
        {text}
      </span>
    </span>
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

// base: 비교 기준이 되는 결과 (서버가 준 첫 번째 레벨, clang/gcc에서는 -O0)
// 에디터에 글자를 칠 때마다 App이 다시 그려진다. 어셈블리가 수만 줄이면 diff가 오래 걸리므로
// memo와 useMemo로 결과가 바뀔 때만 계산한다.
const OptimizationColumn = memo(function OptimizationColumn({ result, base }) {
  const isBaseline = result.level === base.level
  const baseAssembly = !isBaseline && base.success ? base.assembly : null
  const comparing = result.success && baseAssembly != null
  const diffSummary = useMemo(
    () => (comparing ? diffLines(baseAssembly.split('\n'), result.assembly.split('\n')) : null),
    [comparing, baseAssembly, result.assembly],
  )

  return (
    <div className="opt-column">
      <div className="opt-header">
        <h3>
          {result.levelLabel}
          <InfoTooltip text={result.levelDescription} />
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
            {comparing && !diffSummary && (
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
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const [compilers, setCompilers] = useState(FALLBACK_COMPILERS)
  const [language, setLanguage] = useState(INITIAL_LANGUAGE)
  const [selectedCompiler, setSelectedCompiler] = useState(FALLBACK_COMPILERS[0].id)
  const [runBenchmark, setRunBenchmark] = useState(false)

  const languages = groupByLanguage(compilers)
  const currentLanguage = languages.find((l) => l.id === language) ?? languages[0]
  const sampleCode = SAMPLE_CODE[currentLanguage.id] ?? ''

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
        <textarea
          className="code-editor"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          spellCheck={false}
        />

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

          <label className="benchmark-toggle">
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

      {results && results.length > 0 && (
        <section className="results-section-wrap">
          <p className="results-meta">
            {results[0].compilerLabel || selectedCompiler} 기준 비교 결과
          </p>
          <div className="results-section">
            {results.map((r) => (
              <OptimizationColumn key={r.level} result={r} base={results[0]} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

export default App
