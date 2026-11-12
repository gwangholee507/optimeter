import { describe, expect, it } from 'vitest'
import { remarkTopic, summarizeRemarks } from './remarks'

describe('summarizeRemarks', () => {
  it('적용된 remark와 놓친 remark를 따로 센다', () => {
    const remarks = [
      { kind: 'APPLIED', count: 1 },
      { kind: 'MISSED', count: 1 },
      { kind: 'APPLIED', count: 1 },
    ]

    expect(summarizeRemarks(remarks)).toEqual({ applied: 2, missed: 1 })
  })

  it('하나로 합쳐진 remark는 반복 횟수와 상관없이 한 건으로 센다', () => {
    expect(summarizeRemarks([{ kind: 'MISSED', count: 3 }])).toEqual({ applied: 0, missed: 1 })
  })

  it('remark가 없으면 둘 다 0이다', () => {
    expect(summarizeRemarks([])).toEqual({ applied: 0, missed: 0 })
  })
})

describe('remarkTopic', () => {
  it('서버가 아는 최적화 종류면 한국어 이름을 쓴다', () => {
    expect(remarkTopic({ topicLabel: '루프 벡터화', pass: 'loop-vectorize' })).toBe('루프 벡터화')
  })

  it('모르는 종류면 컴파일러가 붙인 패스 이름을 쓴다', () => {
    expect(remarkTopic({ topicLabel: null, pass: 'brand-new-pass' })).toBe('brand-new-pass')
  })

  it('GCC처럼 종류도 패스 이름도 없으면 null이다', () => {
    expect(remarkTopic({ topicLabel: null, pass: null })).toBeNull()
  })
})
