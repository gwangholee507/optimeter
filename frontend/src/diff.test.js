import { describe, it, expect } from 'vitest'
import { MAX_DIFF_CELLS, diffLines } from './diff'

describe('diffLines', () => {
  it('완전히 동일한 두 배열은 모두 same, added/removed 0개', () => {
    const lines = ['mov w0, w0', 'ret']
    const { lineStates, addedCount, removedCount } = diffLines(lines, lines)

    expect(lineStates).toEqual(['same', 'same'])
    expect(addedCount).toBe(0)
    expect(removedCount).toBe(0)
  })

  it('target에만 있는 줄은 added로 표시된다', () => {
    const base = ['a', 'b']
    const target = ['a', 'b', 'c']

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    expect(lineStates).toEqual(['same', 'same', 'added'])
    expect(addedCount).toBe(1)
    expect(removedCount).toBe(0)
  })

  it('base에만 있는 줄은 removedCount로 집계된다 (target에는 나타나지 않음)', () => {
    const base = ['a', 'b', 'c']
    const target = ['a', 'c']

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    // target 기준으로만 상태를 매기므로 lineStates 길이는 target과 같다
    expect(lineStates).toEqual(['same', 'same'])
    expect(addedCount).toBe(0)
    expect(removedCount).toBe(1)
  })

  it('O0 -> O1 최적화로 줄어드는 실제 시나리오를 흉내낸다', () => {
    const o0 = [
      'sub sp, sp, #16',
      'str w0, [sp, #12]',
      'ldr w8, [sp, #12]',
      'ldr w9, [sp, #12]',
      'mul w0, w8, w9',
      'add sp, sp, #16',
      'ret',
    ]
    const o1 = ['mul w0, w0, w0', 'ret']

    const { lineStates, addedCount, removedCount } = diffLines(o0, o1)

    // o1의 'mul w0, w0, w0'는 o0에 없는 새 줄, 'ret'는 공통
    expect(lineStates).toEqual(['added', 'same'])
    expect(addedCount).toBe(1)
    expect(removedCount).toBe(6)
  })

  it('빈 배열끼리는 아무 변화도 없다', () => {
    const { lineStates, addedCount, removedCount } = diffLines([], [])
    expect(lineStates).toEqual([])
    expect(addedCount).toBe(0)
    expect(removedCount).toBe(0)
  })

  it('base가 비어있으면 target의 모든 줄이 added', () => {
    const { lineStates, addedCount, removedCount } = diffLines([], ['x', 'y'])
    expect(lineStates).toEqual(['added', 'added'])
    expect(addedCount).toBe(2)
    expect(removedCount).toBe(0)
  })

  it('target이 비어있으면 removedCount는 base 전체 길이', () => {
    const { lineStates, addedCount, removedCount } = diffLines(['x', 'y'], [])
    expect(lineStates).toEqual([])
    expect(addedCount).toBe(0)
    expect(removedCount).toBe(2)
  })

  it('앞뒤가 같고 가운데만 다르면 가운데 줄만 표시한다', () => {
    const base = ['head', 'old-1', 'old-2', 'tail']
    const target = ['head', 'new', 'tail']

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    expect(lineStates).toEqual(['same', 'added', 'same'])
    expect(addedCount).toBe(1)
    expect(removedCount).toBe(2)
  })

  it('같은 줄이 여러 번 나와도 순서가 맞는 줄만 same이다', () => {
    const base = ['a', 'b', 'a', 'c']
    const target = ['b', 'a', 'd', 'c']

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    expect(lineStates).toEqual(['same', 'same', 'added', 'same'])
    expect(addedCount).toBe(1)
    expect(removedCount).toBe(1)
  })

  it('.file 지시어는 이름이 달라도 같은 줄로 본다', () => {
    const base = ['\t.text', '\t.file\t"3j9vqzn1"', 'add:', '\tret']
    const target = ['\t.text', '\t.file\t"8kf2xw7c"', 'add:', '\tret']

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    expect(lineStates).toEqual(['same', 'same', 'same', 'same'])
    expect(addedCount).toBe(0)
    expect(removedCount).toBe(0)
  })

  it('.file로 시작하지 않는 줄은 그대로 비교한다', () => {
    const { addedCount, removedCount } = diffLines(['\t.filename a'], ['\t.filename b'])

    expect(addedCount).toBe(1)
    expect(removedCount).toBe(1)
  })

  it('수만 줄짜리 어셈블리도 비교한다', () => {
    const base = Array.from({ length: 12_000 }, (_, i) => `base ${i}`)
    const target = Array.from({ length: 4_000 }, (_, i) => (i % 2 ? `base ${i * 3}` : `target ${i}`))

    const { lineStates, addedCount, removedCount } = diffLines(base, target)

    expect(lineStates).toHaveLength(target.length)
    expect(addedCount).toBe(2_000)
    expect(removedCount).toBe(10_000)
  })

  it('표가 상한을 넘으면 비교를 건너뛴다', () => {
    const lines = (prefix) => Array.from({ length: 13_000 }, (_, i) => `${prefix} ${i}`)

    expect(13_000 * 13_000).toBeGreaterThan(MAX_DIFF_CELLS)
    expect(diffLines(lines('base'), lines('target'))).toBeNull()
  })

  it('앞뒤의 같은 줄을 덜어내고 상한 안에 들면 비교한다', () => {
    const same = Array.from({ length: 50_000 }, (_, i) => `same ${i}`)

    const { lineStates, addedCount } = diffLines([...same, 'old'], [...same, 'new'])

    expect(lineStates).toHaveLength(50_001)
    expect(addedCount).toBe(1)
  })
})
