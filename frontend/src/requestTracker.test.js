import { describe, it, expect } from 'vitest'
import { createRequestTracker } from './requestTracker'

describe('createRequestTracker', () => {
  it('방금 시작한 요청은 현재 요청이다', () => {
    const tracker = createRequestTracker()
    const isCurrent = tracker.start()

    expect(isCurrent()).toBe(true)
  })

  it('새 요청을 시작하면 이전 요청은 현재 요청이 아니다', () => {
    const tracker = createRequestTracker()
    const first = tracker.start()
    const second = tracker.start()

    expect(first()).toBe(false)
    expect(second()).toBe(true)
  })

  it('invalidate()하면 진행 중이던 요청이 모두 현재 요청이 아니게 된다', () => {
    // 비교하는 중에 언어를 바꾼 경우: 이전 언어로 보낸 요청의 응답은 늦게 와도 버려야 한다
    const tracker = createRequestTracker()
    const isCurrent = tracker.start()
    tracker.invalidate()

    expect(isCurrent()).toBe(false)
  })

  it('invalidate() 뒤에 시작한 요청은 다시 현재 요청이다', () => {
    const tracker = createRequestTracker()
    tracker.start()
    tracker.invalidate()
    const isCurrent = tracker.start()

    expect(isCurrent()).toBe(true)
  })

  it('트래커끼리는 서로 영향을 주지 않는다', () => {
    const a = createRequestTracker()
    const b = createRequestTracker()
    const isCurrent = a.start()
    b.invalidate()

    expect(isCurrent()).toBe(true)
  })
})
