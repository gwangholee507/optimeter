import { describe, expect, it } from 'vitest'
import { locateLine } from './sourceLines'

const CODE = ['int square(int x) {', '    return x * x;', '}', '', '\tint y;'].join('\n')

describe('locateLine', () => {
  it('첫 줄이면 맨 앞에 커서를 둔다', () => {
    expect(locateLine(CODE, 1)).toEqual({ line: 1, caret: 0 })
  })

  it('들여쓰기를 건너뛰고 그 줄의 첫 글자 앞에 커서를 둔다', () => {
    const { line, caret } = locateLine(CODE, 2)

    expect(line).toBe(2)
    expect(CODE.slice(caret)).toMatch(/^return x \* x;/)
  })

  it('탭으로 들여쓴 줄도 첫 글자 앞으로 간다', () => {
    const { caret } = locateLine(CODE, 5)

    expect(CODE.slice(caret)).toBe('int y;')
  })

  it('빈 줄이면 그 줄의 자리(다음 줄바꿈 앞)로 간다', () => {
    const { line, caret } = locateLine(CODE, 4)

    expect(line).toBe(4)
    expect(CODE[caret - 1]).toBe('\n')
    expect(CODE[caret]).toBe('\n')
  })

  it('줄 수보다 큰 번호는 마지막 줄로 맞춘다 (비교한 뒤 코드를 줄인 경우)', () => {
    expect(locateLine(CODE, 99).line).toBe(5)
  })

  it('0이나 음수, 숫자가 아닌 값은 첫 줄로 맞춘다', () => {
    expect(locateLine(CODE, 0).line).toBe(1)
    expect(locateLine(CODE, -3).line).toBe(1)
    expect(locateLine(CODE, undefined).line).toBe(1)
  })

  it('빈 코드에서도 첫 줄 맨 앞을 돌려준다', () => {
    expect(locateLine('', 7)).toEqual({ line: 1, caret: 0 })
  })

  it('Windows 줄바꿈(\\r\\n)이 섞여 있어도 줄 번호는 \\n 기준으로 센다', () => {
    const text = 'a\r\n  b\r\nc'
    const { caret } = locateLine(text, 2)

    expect(text.slice(caret)).toBe('b\r\nc')
  })
})
