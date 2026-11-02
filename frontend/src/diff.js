// LCS 표의 칸 수 상한 (줄 수의 곱). 칸마다 2바이트라 300MB쯤 되며, 이보다 크면 비교를 건너뛴다.
// Rust는 짧은 프로그램도 -O0에서 수만 줄이 나오므로 표를 무한정 키울 수 없다.
export const MAX_DIFF_CELLS = 150_000_000

// .file은 컴파일 단위의 이름이지 코드가 아니다. rustc는 -C incremental을 쓰면 이 이름이 레벨마다 달라진다.
const FILE_DIRECTIVE = /^\s*\.file\s/

function comparableLine(line) {
  return FILE_DIRECTIVE.test(line) ? '.file' : line
}

/** 줄을 정수로 바꿔서, 표를 채울 때 문자열 대신 정수를 비교한다. */
function internLines(base, target) {
  const ids = new Map()
  const idOf = (line) => {
    let id = ids.get(line)
    if (id === undefined) {
      id = ids.size
      ids.set(line, id)
    }
    return id
  }
  return [Int32Array.from(base, idOf), Int32Array.from(target, idOf)]
}

/**
 * 두 줄 배열(base, target)을 LCS(최장 공통 부분 수열) 기반으로 비교합니다.
 * target의 각 줄이 base에도 "같은 순서로" 존재하면 'same', 아니면 'added'로 표시하고,
 * base에만 있고 target에는 없는 줄의 개수를 removedCount로 셉니다.
 *
 * O(n*m) DP라서 앞뒤의 같은 줄을 먼저 덜어내고, 그래도 표가 MAX_DIFF_CELLS를 넘으면 null을 돌려줍니다.
 */
export function diffLines(baseLines, targetLines) {
  const base = baseLines.map(comparableLine)
  const target = targetLines.map(comparableLine)

  // 앞뒤의 같은 줄은 표에 넣지 않는다 (LCS 길이는 그대로다)
  const shorter = Math.min(base.length, target.length)
  let start = 0
  while (start < shorter && base[start] === target[start]) start++
  let baseEnd = base.length
  let targetEnd = target.length
  while (baseEnd > start && targetEnd > start && base[baseEnd - 1] === target[targetEnd - 1]) {
    baseEnd--
    targetEnd--
  }

  const n = baseEnd - start
  const m = targetEnd - start
  const width = m + 1
  if ((n + 1) * width > MAX_DIFF_CELLS) return null

  const [a, b] = internLines(base.slice(start, baseEnd), target.slice(start, targetEnd))

  // dp[i * width + j] = a[i..] 와 b[j..] 의 LCS 길이.
  // 값은 짧은 쪽 줄 수를 넘지 못하고, 칸 수 상한 때문에 짧은 쪽은 65,535줄을 넘을 수 없다.
  const dp = new Uint16Array((n + 1) * width)
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i * width + j] =
        a[i] === b[j]
          ? dp[(i + 1) * width + j + 1] + 1
          : Math.max(dp[(i + 1) * width + j], dp[i * width + j + 1])
    }
  }

  const lineStates = new Array(start).fill('same') // target 각 줄에 대응: 'same' | 'added'
  let removedCount = 0
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      lineStates.push('same')
      i++
      j++
    } else if (dp[(i + 1) * width + j] >= dp[i * width + j + 1]) {
      removedCount++
      i++
    } else {
      lineStates.push('added')
      j++
    }
  }
  removedCount += n - i
  while (j < m) {
    lineStates.push('added')
    j++
  }
  while (lineStates.length < target.length) lineStates.push('same')

  const addedCount = lineStates.filter((s) => s === 'added').length
  return { lineStates, addedCount, removedCount }
}
