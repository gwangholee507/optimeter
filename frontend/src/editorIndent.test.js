import { describe, it, expect } from 'vitest'
import { INDENT, indentSelection } from './editorIndent'

// indentSelection이 돌려준 대로 바꾼 결과 글자와 선택 범위
function apply(text, start, end) {
  const edit = indentSelection(text, start, end)
  return {
    text: text.slice(0, edit.start) + edit.insert + text.slice(edit.end),
    selection: [edit.selectionStart, edit.selectionEnd],
  }
}

describe('indentSelection', () => {
  it('들여쓰기는 공백 4칸이다', () => {
    expect(INDENT).toBe('    ')
  })

  it('선택이 없으면 커서 자리에 공백 4칸을 넣고 커서를 그 뒤로 옮긴다', () => {
    expect(apply('ab', 1, 1)).toEqual({ text: 'a    b', selection: [5, 5] })
  })

  it('빈 입력창에서도 공백 4칸을 넣는다', () => {
    expect(apply('', 0, 0)).toEqual({ text: '    ', selection: [4, 4] })
  })

  it('한 줄 안에서 고른 글자는 공백 4칸으로 바꾼다', () => {
    expect(apply('int x;\nint y;', 4, 5)).toEqual({ text: 'int     ;\nint y;', selection: [8, 8] })
  })

  it('여러 줄을 고르면 걸친 줄마다 앞에 공백 4칸을 넣고 그 줄들을 통째로 선택한다', () => {
    const text = 'a;\nb;\nc;\nd;'
    // "b;"의 가운데부터 "c;"의 가운데까지
    expect(apply(text, 4, 7)).toEqual({ text: 'a;\n    b;\n    c;\nd;', selection: [3, 16] })
  })

  it('맨 첫 줄부터 고른 경우도 첫 줄을 들여쓴다', () => {
    expect(apply('a;\nb;', 0, 4)).toEqual({ text: '    a;\n    b;', selection: [0, 13] })
  })

  it('첫 글자가 줄바꿈이어도 맨 앞부터 고른 선택은 첫 줄(빈 줄)부터 센다', () => {
    expect(apply('\na;\nb;', 0, 5)).toEqual({ text: '\n    a;\n    b;', selection: [0, 14] })
  })

  it('선택이 다음 줄의 맨 앞에서 끝나면 그 줄은 들여쓰지 않는다', () => {
    // "a;\nb;\n"까지 끌어서 고르면 c;는 건드리지 않는다
    expect(apply('a;\nb;\nc;', 0, 6)).toEqual({ text: '    a;\n    b;\nc;', selection: [0, 13] })
  })

  it('한 줄을 줄바꿈까지 통째로 고르면 그 줄만 들여쓴다', () => {
    expect(apply('a;\nb;', 0, 3)).toEqual({ text: '    a;\nb;', selection: [0, 6] })
  })

  it('고른 줄 중 빈 줄에는 공백을 넣지 않는다', () => {
    expect(apply('a;\n\nb;', 0, 6)).toEqual({ text: '    a;\n\n    b;', selection: [0, 14] })
  })

  it('이미 들여쓴 줄은 4칸을 더 들여쓴다', () => {
    expect(apply('if (x) {\n    y();\n}', 9, 19)).toEqual({
      text: 'if (x) {\n        y();\n    }',
      selection: [9, 27],
    })
  })
})
