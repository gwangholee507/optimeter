import { describe, it, expect } from 'vitest'
import { INDENT, indentSelection, outdentSelection } from './editorIndent'

// 계산한 대로 바꾼 결과 글자와 선택 범위
function applyEdit(text, edit) {
  return {
    text: text.slice(0, edit.start) + edit.insert + text.slice(edit.end),
    selection: [edit.selectionStart, edit.selectionEnd],
  }
}

const apply = (text, start, end) => applyEdit(text, indentSelection(text, start, end))
const applyOutdent = (text, start, end) => applyEdit(text, outdentSelection(text, start, end))

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

describe('outdentSelection', () => {
  it('커서가 줄 가운데에 있어도 줄 맨 앞의 공백 4칸을 지우고 커서를 그만큼 왼쪽으로 옮긴다', () => {
    expect(applyOutdent('    return x;', 8, 8)).toEqual({ text: 'return x;', selection: [4, 4] })
  })

  it('커서가 있는 줄만 내어쓴다', () => {
    expect(applyOutdent('a {\n        b;\n}', 14, 14)).toEqual({ text: 'a {\n    b;\n}', selection: [10, 10] })
  })

  it('공백이 4칸보다 적으면 있는 만큼만 지운다', () => {
    expect(applyOutdent('  x', 3, 3)).toEqual({ text: 'x', selection: [1, 1] })
  })

  it('커서가 지운 공백 안에 있었으면 줄 맨 앞으로 간다', () => {
    expect(applyOutdent('      x', 2, 2)).toEqual({ text: '  x', selection: [0, 0] })
  })

  it('맨 앞이 탭 문자면 그 하나를 지운다', () => {
    expect(applyOutdent('\tx', 2, 2)).toEqual({ text: 'x', selection: [1, 1] })
  })

  it('한 줄 안에서 고른 범위는 지운 칸만큼 왼쪽으로 옮겨 그대로 선택해 둔다', () => {
    expect(applyOutdent('        foo();', 8, 11)).toEqual({ text: '    foo();', selection: [4, 7] })
  })

  it('지울 들여쓰기가 없으면 null을 돌려준다', () => {
    expect(outdentSelection('x;', 1, 1)).toBeNull()
    expect(outdentSelection('', 0, 0)).toBeNull()
    expect(outdentSelection('a;\nb;', 0, 5)).toBeNull()
  })

  it('여러 줄을 고르면 걸친 줄마다 한 단계씩 내어쓰고 그 줄들을 통째로 선택한다', () => {
    expect(applyOutdent('    a;\n        b;\nc;', 2, 10)).toEqual({ text: 'a;\n    b;\nc;', selection: [0, 9] })
  })

  it('고른 줄 중 들여쓰기가 없는 줄은 그대로 둔다', () => {
    expect(applyOutdent('a;\n    b;', 0, 9)).toEqual({ text: 'a;\nb;', selection: [0, 5] })
  })

  it('선택이 다음 줄의 맨 앞에서 끝나면 그 줄은 내어쓰지 않는다', () => {
    expect(applyOutdent('    a;\n    b;', 0, 7)).toEqual({ text: 'a;\n    b;', selection: [0, 3] })
  })

  it('여러 줄을 Tab으로 들여쓴 뒤 바로 Shift+Tab을 누르면 원래대로 돌아온다', () => {
    const text = 'int f() {\n    return 1;\n}'
    const indented = apply(text, 0, text.length)
    expect(applyOutdent(indented.text, ...indented.selection)).toEqual({ text, selection: [0, text.length] })
  })
})
