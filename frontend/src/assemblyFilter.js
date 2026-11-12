// 어셈블리에서 실행되는 코드와 데이터만 남기고, 사람이 읽을 필요가 없는 줄을 숨긴다.
// 컴파일러는 어셈블러와 링커에게 주는 정보(.cfi_*, .section, .p2align 같은 지시어)를 코드 사이에 섞어서 내놓는데,
// 이 줄들이 diff에 섞이면 최적화가 실제로 바꾼 줄을 찾기 어렵다.
//
// 숨기는 줄:
// - 지시어. 단 .byte, .long, .asciz처럼 데이터를 만드는 지시어는 상수 문자열이나 전역 변수의 값이라서 남긴다
// - 주석만 있는 줄과 빈 줄 (# %bb.0:, ; -- End function 같은 줄)
// - 지시어에서만 쓰는 컴파일러 내부 레이블 (.Lfunc_end0:, Lloh0: 같은 줄)과 그 레이블에 딸린 데이터
// - 디버그 정보, 예외 처리 표처럼 코드와 상관없는 섹션의 내용 전체
//
// clang, GCC, rustc 모두 GNU 어셈블러 문법으로 내놓으므로 하나의 규칙으로 macOS(Mach-O), Linux(ELF),
// Windows(COFF) 출력을 모두 다룬다.

// 줄 끝 주석의 시작. x86은 #, Apple arm64는 ;, Linux arm64는 //를 쓴다.
const COMMENT_START = /^(#|;|\/\/|\/\*)/

// "이름:" 뒤에 주석만 오는 줄. Windows의 MSVC식 이름은 따옴표로 감싸서 나온다 ("??_C@_01...@":).
const LABEL = /^("[^"]*"|[^\s:"]+):\s*(.*)$/

// name = value 꼴의 기호 정의 (Windows의 @feat.00 = 0)
const ASSIGNMENT = /^[\w.$@"]+\s*=\s/

// 값을 만드는 지시어. 레이블에 딸린 데이터라서 그 레이블을 보여줄 때만 함께 보여준다.
const DATA_DIRECTIVE =
  /^\.(ascii|asciz|string|base64|byte|[248]byte|short|hword|half|value|word|int|long|dword|quad|xword|octa|float|single|double|zero|space|skip|fill)\b/

// 레이블 없이 스스로 변수를 정의하는 지시어 (초기값이 없는 전역 변수). 항상 보여준다.
const SYMBOL_DIRECTIVE = /^\.(comm|lcomm|zerofill|tbss)\b/

// 섹션을 바꾸는 지시어
const SECTION_DIRECTIVE = /^\.(section|pushsection)\s+(.*)$/
const SHORT_SECTION_DIRECTIVE = /^\.(text|data|bss|rodata|const|const_data|cstring|literal\d+)\b/
const POP_SECTION = /^\.popsection\b/
const PREVIOUS_SECTION = /^\.previous\b/

// 내용을 통째로 숨길 섹션: 디버그 정보, 예외 처리 표, 되감기(unwind) 정보, 링커용 메모.
// ELF와 COFF는 섹션 이름(.debug_info, .debug$S), Mach-O는 "세그먼트,섹션"(__DWARF,__debug_info)으로 나온다.
const METADATA_SECTION =
  /^"?(\.debug|\.note|\.comment|\.gcc_except_table|\.eh_frame|\.llvm_addrsig|\.xdata|\.pdata|__DWARF,|__TEXT,__gcc_except_tab|__TEXT,__eh_frame|__LD,__compact_unwind)/

// Mach-O(macOS) 출력에만 나오는 지시어. 레이블 이름 규칙이 달라서 구분해야 한다.
const DARWIN_MARKER = /^\s*\.(subsections_via_symbols|build_version|macosx_version_min)\b/m

// 줄에서 기호 이름이 될 수 있는 낱말 (LBB1_2, .L.str, l_.str.2@PAGEOFF의 l_.str.2)
const IDENTIFIER = /[A-Za-z_.$][\w.$]*/g

/**
 * 컴파일러가 만든 내부 레이블인지. 내부 레이블은 아무 코드도 가리키지 않으면 숨긴다.
 * - ELF와 COFF는 .L로 시작한다 (.LBB0_2, .Lfunc_end0, .L.str).
 * - Mach-O는 C 함수와 변수 이름 앞에 항상 _가 붙고, 내부 레이블은 L이나 l로 시작한다 (LBB0_2, Lloh0, l_.str).
 *   Linux에서는 사용자 함수 이름이 그대로 나오므로(lerp:) 이 규칙을 쓰면 안 된다.
 * - 예외 처리 표의 레이블(GCC_except_table0)은 어느 형식이든 _나 .L 없이 나온다.
 */
function isInternalLabel(name, darwin) {
  if (name.startsWith('.L') || name.startsWith('GCC_except_table')) return true
  return darwin && (name.startsWith('L') || name.startsWith('l'))
}

function addIdentifiers(text, into) {
  for (const match of text.matchAll(IDENTIFIER)) into.add(match[0])
}

/** 줄 하나를 분류한다. 섹션 상태는 parse가 따로 관리한다. */
function classify(line) {
  const text = line.trim()
  if (text === '' || COMMENT_START.test(text)) return { kind: 'hidden' }

  const label = LABEL.exec(text)
  if (label) {
    const rest = label[2]
    // "이름: 명령어"처럼 한 줄에 함께 쓴 경우는 명령어로 본다
    if (rest === '' || COMMENT_START.test(rest)) return { kind: 'label', name: label[1] }
    return { kind: 'instruction' }
  }

  if (SYMBOL_DIRECTIVE.test(text)) return { kind: 'symbol' }
  if (DATA_DIRECTIVE.test(text)) return { kind: 'data' }
  if (text.startsWith('.') || ASSIGNMENT.test(text)) return { kind: 'hidden' }
  return { kind: 'instruction' }
}

/**
 * 줄마다 종류와, 데이터 줄이면 딸린 레이블(같은 섹션에서 가장 가까운 앞 레이블의 줄 번호, 없으면 -1)을 매긴다.
 * 숨길 섹션 안의 줄은 종류와 상관없이 'hidden'이다.
 */
function parse(lines) {
  const records = new Array(lines.length)
  let metadata = false
  let previousMetadata = false
  const stack = []
  let owner = -1

  const switchSection = (next) => {
    previousMetadata = metadata
    metadata = next
    owner = -1
  }

  for (let i = 0; i < lines.length; i++) {
    const text = lines[i].trim()
    const section = SECTION_DIRECTIVE.exec(text)
    if (section) {
      if (section[1] === 'pushsection') stack.push(metadata)
      switchSection(METADATA_SECTION.test(section[2]))
      records[i] = { kind: 'hidden' }
      continue
    }
    if (SHORT_SECTION_DIRECTIVE.test(text)) {
      switchSection(false)
      records[i] = { kind: 'hidden' }
      continue
    }
    if (POP_SECTION.test(text)) {
      switchSection(stack.length > 0 ? stack.pop() : false)
      records[i] = { kind: 'hidden' }
      continue
    }
    if (PREVIOUS_SECTION.test(text)) {
      switchSection(previousMetadata)
      records[i] = { kind: 'hidden' }
      continue
    }

    if (metadata) {
      records[i] = { kind: 'hidden' }
      continue
    }

    const record = classify(lines[i])
    if (record.kind === 'label') owner = i
    if (record.kind === 'data') record.owner = owner
    records[i] = record
  }
  return records
}

/**
 * 어셈블리 텍스트에서 실행되는 명령어, 데이터, 그리고 그것들이 가리키는 레이블만 남긴 텍스트를 돌려준다.
 *
 * 내부 레이블은 남은 명령어나 데이터가 그 이름을 쓸 때만 보여준다. 데이터는 다른 데이터의 레이블을 가리킬 수 있어서
 * (포인터 표가 문자열을 가리키는 경우) 더 늘지 않을 때까지 반복한다.
 */
export function filterAssembly(assembly) {
  const lines = assembly.split('\n')
  const darwin = DARWIN_MARKER.test(assembly)
  const records = parse(lines)

  const referenced = new Set()
  records.forEach((record, i) => {
    if (record.kind === 'instruction' || record.kind === 'symbol') addIdentifiers(lines[i], referenced)
  })

  const labelShown = (index) => {
    const record = records[index]
    return !isInternalLabel(record.name, darwin) || referenced.has(record.name)
  }
  const dataShown = (record) => record.owner === -1 || labelShown(record.owner)

  const counted = new Set()
  let grew = true
  while (grew) {
    const before = referenced.size
    records.forEach((record, i) => {
      if (record.kind === 'data' && !counted.has(i) && dataShown(record)) {
        counted.add(i)
        addIdentifiers(lines[i], referenced)
      }
    })
    grew = referenced.size > before
  }

  return lines
    .filter((_, i) => {
      const record = records[i]
      switch (record.kind) {
        case 'instruction':
        case 'symbol':
          return true
        case 'label':
          return labelShown(i)
        case 'data':
          return dataShown(record)
        default:
          return false
      }
    })
    .join('\n')
}
