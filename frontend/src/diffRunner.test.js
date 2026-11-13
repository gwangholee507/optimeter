import { afterEach, describe, it, expect, vi } from 'vitest'
import { diffLines } from './diff'
import { createDiffRunner } from './diffRunner'

// 진짜 worker 대신 쓰는 가짜. 테스트가 respond/fail을 불러야 응답한다.
class FakeWorker {
  constructor() {
    this.terminated = false
    this.received = null
  }

  postMessage(data) {
    this.received = data
  }

  respond() {
    const { base, target } = this.received
    this.onmessage({ data: diffLines(base.split('\n'), target.split('\n')) })
  }

  fail() {
    this.onerror({ message: 'worker 스크립트를 불러오지 못함', preventDefault() {} })
  }

  terminate() {
    this.terminated = true
  }
}

function setUp(maxConcurrent = 2) {
  const workers = []
  const runDiff = createDiffRunner({
    maxConcurrent,
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  })
  return { workers, runDiff }
}

const BASE = 'push rbp\nmov eax, edi\nimul eax, edi\npop rbp\nret'
const TARGET = 'mov eax, edi\nimul eax, edi\nret'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('createDiffRunner', () => {
  it('worker가 계산한 결과는 diffLines와 같고, 결과를 받으면 worker를 닫는다', async () => {
    const { workers, runDiff } = setUp()

    const job = runDiff(BASE, TARGET)
    expect(workers[0].received).toEqual({ base: BASE, target: TARGET })
    workers[0].respond()

    await expect(job.promise).resolves.toEqual(diffLines(BASE.split('\n'), TARGET.split('\n')))
    expect(workers[0].terminated).toBe(true)
  })

  it('정해진 수까지만 동시에 계산하고, 하나가 끝나면 기다리던 비교를 시작한다', () => {
    const { workers, runDiff } = setUp(2)

    runDiff(BASE, 'a')
    runDiff(BASE, 'b')
    runDiff(BASE, 'c')
    expect(workers).toHaveLength(2)

    workers[0].respond()
    expect(workers).toHaveLength(3)
    expect(workers[2].received.target).toBe('c')
  })

  it('기다리던 비교를 취소하면 worker를 띄우지 않는다', () => {
    const { workers, runDiff } = setUp(1)

    runDiff(BASE, 'a')
    const waiting = runDiff(BASE, 'b')
    waiting.cancel()
    workers[0].respond()

    expect(workers).toHaveLength(1)
  })

  it('계산 중인 비교를 취소하면 worker를 바로 닫고 다음 비교를 시작한다', async () => {
    const { workers, runDiff } = setUp(1)
    const onResult = vi.fn()

    const running = runDiff(BASE, 'a')
    running.promise.then(onResult)
    runDiff(BASE, 'b')
    running.cancel()
    await Promise.resolve()

    expect(workers[0].terminated).toBe(true)
    expect(workers).toHaveLength(2)
    expect(onResult).not.toHaveBeenCalled()
  })

  it('worker를 띄우지 못하면 화면 스레드에서 계산한다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const runDiff = createDiffRunner({
      createWorker: () => {
        throw new Error('Worker is not defined')
      },
    })

    await expect(runDiff(BASE, TARGET).promise).resolves.toEqual(diffLines(BASE.split('\n'), TARGET.split('\n')))
  })

  it('worker가 실패하면 화면 스레드에서 계산하고, 기다리던 비교를 이어서 시작한다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { workers, runDiff } = setUp(1)

    const job = runDiff(BASE, TARGET)
    runDiff(BASE, 'b')
    workers[0].fail()

    await expect(job.promise).resolves.toEqual(diffLines(BASE.split('\n'), TARGET.split('\n')))
    expect(workers).toHaveLength(2)
  })
})
