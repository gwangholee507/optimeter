// 코드 입력창에서 Tab을 눌렀을 때 넣을 들여쓰기 (공백 4칸)
export const INDENT = '    '

// 코드 입력창에서 Tab을 눌렀을 때 글자를 어떻게 바꿀지 계산한다. 입력창을 직접 건드리지 않는 순수 함수.
// text: 입력창의 전체 내용, start/end: 선택 범위(selectionStart/selectionEnd. 선택이 없으면 둘이 같다)
// 돌려주는 값: [start, end) 구간을 insert로 바꾼 뒤 selectionStart~selectionEnd를 선택하면 된다
// - 선택이 없거나 한 줄 안이면: 그 자리(선택한 글자는 지우고)에 공백 4칸을 넣고, 커서를 그 뒤에 둔다
// - 여러 줄을 선택했으면: 선택이 걸친 줄마다 앞에 공백 4칸을 넣고(빈 줄은 건너뜀), 그 줄들 전체를 선택한다.
//   선택이 다음 줄의 맨 앞에서 끝나면(줄을 통째로 끌어서 선택한 경우) 그 줄은 걸친 것으로 치지 않는다
export function indentSelection(text, start, end) {
  if (!text.slice(start, end).includes('\n')) {
    const caret = start + INDENT.length
    return { start, end, insert: INDENT, selectionStart: caret, selectionEnd: caret }
  }

  // lastIndexOf에 -1을 주면 0번 글자를 보므로, 맨 앞에서 시작한 선택은 따로 처리한다
  const blockStart = start === 0 ? 0 : text.lastIndexOf('\n', start - 1) + 1
  const lastIncluded = text[end - 1] === '\n' ? end - 1 : end
  const lineEnd = text.indexOf('\n', lastIncluded)
  const blockEnd = lineEnd === -1 ? text.length : lineEnd

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
