// 코드 입력창에서 Tab을 눌렀을 때 넣을 들여쓰기 (공백 4칸)
export const INDENT = '    '

// 아래 두 함수는 Tab/Shift+Tab을 눌렀을 때 글자를 어떻게 바꿀지 계산한다. 입력창을 직접 건드리지 않는 순수 함수.
// text: 입력창의 전체 내용, start/end: 선택 범위(selectionStart/selectionEnd. 선택이 없으면 둘이 같다)
// 돌려주는 값: [start, end) 구간을 insert로 바꾼 뒤 selectionStart~selectionEnd를 선택하면 된다

// 선택이 걸친 줄들의 범위 [시작, 끝). 끝은 마지막 줄의 줄바꿈 바로 앞이다.
// 여러 줄 선택이 다음 줄의 맨 앞에서 끝나면(줄을 통째로 끌어서 선택한 경우) 그 줄은 걸친 것으로 치지 않는다
function selectedLines(text, start, end) {
  // lastIndexOf에 -1을 주면 0번 글자를 보므로, 맨 앞에서 시작한 선택은 따로 처리한다
  const blockStart = start === 0 ? 0 : text.lastIndexOf('\n', start - 1) + 1
  const lastIncluded = end > start && text[end - 1] === '\n' ? end - 1 : end
  const lineEnd = text.indexOf('\n', lastIncluded)
  return [blockStart, lineEnd === -1 ? text.length : lineEnd]
}

// Tab
// - 선택이 없거나 한 줄 안이면: 그 자리(선택한 글자는 지우고)에 공백 4칸을 넣고, 커서를 그 뒤에 둔다
// - 여러 줄을 선택했으면: 선택이 걸친 줄마다 앞에 공백 4칸을 넣고(빈 줄은 건너뜀), 그 줄들 전체를 선택한다
export function indentSelection(text, start, end) {
  if (!text.slice(start, end).includes('\n')) {
    const caret = start + INDENT.length
    return { start, end, insert: INDENT, selectionStart: caret, selectionEnd: caret }
  }

  const [blockStart, blockEnd] = selectedLines(text, start, end)
  const insert = text
    .slice(blockStart, blockEnd)
    .split('\n')
    .map((line) => (line === '' ? line : INDENT + line))
    .join('\n')
  return {
    start: blockStart,
    end: blockEnd,
    insert,
    selectionStart: blockStart,
    selectionEnd: blockStart + insert.length,
  }
}

// 줄 맨 앞에서 지울 들여쓰기의 길이: 공백 최대 4칸, 맨 앞이 공백이 아니라 탭 문자면 그 하나
function leadingIndentLength(line) {
  let n = 0
  while (n < INDENT.length && line[n] === ' ') n++
  return n === 0 && line[0] === '\t' ? 1 : n
}

// Shift+Tab: 커서가 있는 줄(여러 줄을 선택했으면 걸친 줄마다)의 맨 앞에서 들여쓰기를 한 단계(공백 최대 4칸) 지운다.
// 커서가 줄의 어디에 있든 줄 맨 앞에서 지운다
// - 한 줄이면: 선택(커서)을 지운 칸만큼 왼쪽으로 옮긴다. 지운 공백 안에 있었으면 줄 맨 앞으로 간다
// - 여러 줄이면: Tab과 같이 그 줄들 전체를 선택한다
// 지울 들여쓰기가 하나도 없으면 null (아무것도 바꾸지 않는다)
export function outdentSelection(text, start, end) {
  const [blockStart, blockEnd] = selectedLines(text, start, end)
  const lines = text.slice(blockStart, blockEnd).split('\n')
  const removed = lines.map(leadingIndentLength)
  if (removed.every((n) => n === 0)) return null

  const insert = lines.map((line, i) => line.slice(removed[i])).join('\n')
  if (lines.length === 1) {
    const shift = (pos) => Math.max(blockStart, pos - removed[0])
    return { start: blockStart, end: blockEnd, insert, selectionStart: shift(start), selectionEnd: shift(end) }
  }
  return {
    start: blockStart,
    end: blockEnd,
    insert,
    selectionStart: blockStart,
    selectionEnd: blockStart + insert.length,
  }
}
