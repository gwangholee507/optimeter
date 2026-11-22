// 최적화 기록의 줄 번호를 눌렀을 때 코드 입력창의 어느 글자로 갈지 계산하는 순수 함수 (입력창을 직접 건드리지 않는다)

/**
 * text의 line번째 줄(1부터)을 찾는다. 줄 번호가 범위를 벗어나면 첫 줄이나 마지막 줄로 맞춘다.
 * 비교한 뒤에 코드를 고쳐서 줄이 줄었어도 엉뚱한 곳이 아니라 가장 가까운 줄로 가게 하려는 것이다.
 *
 * 돌려주는 값
 * - line: 실제로 찾은 줄 번호
 * - caret: 커서를 둘 위치. 들여쓰기를 건너뛴 첫 글자 앞이고, 공백뿐인 줄이면 줄 끝
 *
 * 컴파일러가 알려주는 칸 번호는 쓰지 않는다. clang은 바이트 단위, GCC는 화면 칸 단위(탭과 한글을 넓게 센다)라서
 * 한글이 섞인 줄에서는 입력창의 글자 위치와 맞지 않는다.
 */
export function locateLine(text, line) {
  const lines = text.split('\n')
  const target = Math.min(Math.max(Math.trunc(line) || 1, 1), lines.length)
  let start = 0
  for (let i = 0; i < target - 1; i += 1) start += lines[i].length + 1
  const indent = lines[target - 1].length - lines[target - 1].trimStart().length
  return { line: target, caret: start + indent }
}
