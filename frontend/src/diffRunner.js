import { diffLines } from './diff'

// 동시에 띄우는 worker 수. 비교 하나가 LCS 표에 수백 MB까지 쓸 수 있어서(MAX_DIFF_CELLS) 적게 둔다.
export const MAX_CONCURRENT_DIFFS = 2

function createDiffWorker() {
  return new Worker(new URL('./diff.worker.js', import.meta.url), { type: 'module' })
}

function diffOnThisThread(base, target) {
  return diffLines(base.split('\n'), target.split('\n'))
}

/**
 * 두 어셈블리의 비교(diffLines)를 worker에서 돌리는 함수를 만든다.
 *
 * 비교마다 worker를 새로 띄우고 결과를 받으면 닫는다. 그래서 더 기다릴 필요가 없어진 비교(기준 레벨이나
 * 지시어 필터를 바꾼 경우 등)는 cancel로 계산 도중에도 바로 멈춘다. 대기 중인 비교는 앞에서부터 시작한다.
 * worker를 띄우지 못하거나 worker가 실패하면 예전처럼 화면 스레드에서 계산한다 (느려도 결과는 같다).
 *
 * 돌려주는 함수는 { promise, cancel }을 돌려준다. promise는 diffLines의 결과로 이행되고,
 * cancel한 비교의 promise는 이행되지 않는다.
 */
export function createDiffRunner({ createWorker = createDiffWorker, maxConcurrent = MAX_CONCURRENT_DIFFS } = {}) {
  const waiting = []
  let running = 0

  function startWaiting() {
    while (running < maxConcurrent && waiting.length > 0) {
      running++
      waiting.shift().start()
    }
  }

  return function runDiff(base, target) {
    let state = 'waiting' // 'waiting' → 'running' → 'done'
    let worker = null
    let resolve
    const promise = new Promise((r) => {
      resolve = r
    })

    function finish(summary) {
      if (state === 'done') return
      const wasRunning = state === 'running'
      state = 'done'
      worker?.terminate()
      worker = null
      if (wasRunning) {
        running--
        startWaiting()
      }
      if (summary !== undefined) resolve(summary)
    }

    function fallBack(reason) {
      console.warn('diff worker를 쓸 수 없어 화면 스레드에서 비교합니다:', reason)
      finish(diffOnThisThread(base, target))
    }

    const job = {
      start() {
        state = 'running'
        try {
          worker = createWorker()
        } catch (error) {
          fallBack(error)
          return
        }
        worker.onmessage = (event) => finish(event.data)
        worker.onerror = (event) => {
          event.preventDefault?.()
          fallBack(event.message ?? event)
        }
        worker.postMessage({ base, target })
      },
    }
    waiting.push(job)
    startWaiting()

    return {
      promise,
      cancel() {
        if (state === 'waiting') {
          waiting.splice(waiting.indexOf(job), 1)
          state = 'done'
        } else {
          finish(undefined)
        }
      },
    }
  }
}

export const runDiff = createDiffRunner()
