// 레지스터 이름만 다른 줄을 diff에서 같은 줄로 보기 위해, 비교할 때만 줄의 레지스터 이름을 일반화한다.
// 최적화 레벨이 바뀌면 같은 계산이라도 레지스터 할당이 달라져서(mul w0, w8, w9 → mul w8, w0, w1)
// 원문을 그대로 비교하면 줄 전체가 바뀐 것으로 보인다. 화면에 보이는 어셈블리는 바꾸지 않는다.
//
// 규칙:
// - 줄 안에서 처음 나온 레지스터부터 0, 1, 2 … 번호를 매긴다. 그래서 같은 레지스터를 두 번 쓰는지는 그대로 남는다
//   (xorl %eax, %eax는 0으로 만드는 관용구라 xorl %ecx, %edx와 다르다).
// - 크기와 종류는 그대로 남긴다 (%eax와 %rax, w0과 x0, d0과 q0은 다르다). 같은 물리 레지스터의 다른 크기는
//   같은 번호를 받는다 (movslq %eax, %rax는 제자리 확장이라 movslq %edi, %rax와 다르다).
// - 레지스터 할당과 상관없는 특별한 레지스터는 바꾸지 않는다: x86의 스택 포인터(rsp)·프레임 포인터(rbp)·명령어 포인터(rip)·
//   세그먼트·x87 스택, arm64의 sp·영 레지스터(xzr, wzr)·프레임 포인터(x29)·링크 레지스터(x30).
// - 줄 끝 주석은 비교에서 뺀다. 컴파일러가 명령어를 풀어 적은 것이라(# xmm2 = mem[0],zero) 레지스터 이름이 그대로 다시 나온다.
//   주석만 있는 줄은 그대로 둔다.
// - 따옴표 안(문자열 데이터, Windows의 따옴표로 감싼 기호 이름)은 바꾸지 않는다.
//
// clang, GCC, rustc 모두 GNU 어셈블러 문법(x86은 AT&T 문법)으로 내놓으므로 x86-64와 arm64 두 가지만 다룬다.

/** x86-64 레지스터 이름 → { family: 물리 레지스터, kind: 크기·종류 }. 여기 없는 이름(rsp, rbp, rip 등)은 그대로 둔다. */
const X86_REGISTERS = new Map()
{
  const add = (name, family, kind) => X86_REGISTERS.set(name, { family, kind })
  for (const r of ['a', 'b', 'c', 'd']) {
    add(`r${r}x`, r, 'r64')
    add(`e${r}x`, r, 'r32')
    add(`${r}x`, r, 'r16')
    add(`${r}l`, r, 'r8')
    add(`${r}h`, r, 'r8h')
  }
  for (const r of ['si', 'di']) {
    add(`r${r}`, r, 'r64')
    add(`e${r}`, r, 'r32')
    add(r, r, 'r16')
    add(`${r}l`, r, 'r8')
  }
  // r16~r31은 APX(-mapxf)를 켰을 때만 나온다
  for (let n = 8; n <= 31; n++) {
    add(`r${n}`, `r${n}`, 'r64')
    add(`r${n}d`, `r${n}`, 'r32')
    add(`r${n}w`, `r${n}`, 'r16')
    add(`r${n}b`, `r${n}`, 'r8')
  }
  for (let n = 0; n <= 31; n++) {
    add(`xmm${n}`, `v${n}`, 'xmm')
    add(`ymm${n}`, `v${n}`, 'ymm')
    add(`zmm${n}`, `v${n}`, 'zmm')
  }
  for (let n = 0; n <= 7; n++) {
    add(`k${n}`, `k${n}`, 'k')
    add(`mm${n}`, `mm${n}`, 'mm')
  }
}

// AT&T 문법의 레지스터는 앞에 %가 붙는다 (%eax, %xmm0, %r8d, {%k1})
const X86_REGISTER = /%([a-z][a-z0-9]*)/g
// 이름은 다르지만 레지스터인 것. 어셈블리가 x86인지 가릴 때만 쓴다
const X86_FIXED = new Set(['rsp', 'esp', 'sp', 'spl', 'rbp', 'ebp', 'bp', 'bpl', 'rip', 'eip'])

// arm64 레지스터는 맨 이름으로 나온다. 앞뒤가 이름의 일부이면 레지스터가 아니다 (.LBB0_2, _d1, #0xd1, :lo12:x1).
// 뒤에 .이나 [가 오는 것은 벡터 레지스터의 배치·원소 표기다 (v0.2d, v0.d[0], v2[1]). 뒤에 :가 오면 레이블 정의다.
const ARM64_REGISTER = /(?<![\w.$:@])([xwvqdshb])(\d{1,2})(?![\w$:])/g
const ARM64_GPR_LAST = 28 // x29는 프레임 포인터, x30은 링크 레지스터라 그대로 둔다
const ARM64_FP_LAST = 31

function arm64Register(letter, number) {
  const n = Number(number)
  if (letter === 'x' || letter === 'w') {
    return n <= ARM64_GPR_LAST ? { family: `r${n}`, kind: letter } : null
  }
  return n <= ARM64_FP_LAST ? { family: `v${n}`, kind: letter } : null
}

// 따옴표로 감싼 부분. split하면 홀수 번째 조각이 따옴표 안이다
const QUOTED = /("(?:[^"\\]|\\.)*")/
const ALL_QUOTED = new RegExp(QUOTED.source, 'g')

/** 따옴표 안(문자열 데이터)을 비운 줄. 문자열에 "%eax"나 "x1"이 있어도 레지스터로 보지 않게 한다. */
function withoutStrings(line) {
  return line.includes('"') ? line.replace(ALL_QUOTED, '""') : line
}

/**
 * 어셈블리가 어느 문법인지 가린다: 'x86' | 'arm64' | null(레지스터가 하나도 없음).
 * x86의 %레지스터를 먼저 찾는다. arm64의 맨 이름은 x86에서 기호 이름(전역 변수 s1)과 겹칠 수 있어서,
 * %레지스터가 하나도 없을 때만 arm64로 본다.
 */
export function detectRegisterSyntax(lines) {
  for (const line of lines) {
    for (const match of withoutStrings(line).matchAll(X86_REGISTER)) {
      if (X86_REGISTERS.has(match[1]) || X86_FIXED.has(match[1])) return 'x86'
    }
  }
  for (const line of lines) {
    for (const match of withoutStrings(line).matchAll(ARM64_REGISTER)) {
      if (arm64Register(match[1], match[2])) return 'arm64'
    }
  }
  return null
}

/** 줄 끝 주석이 시작하는 위치 (없으면 줄 길이). 따옴표 안의 #, ;는 주석이 아니다. */
function commentStart(line, syntax) {
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (quoted) {
      if (c === '\\') i++
      else if (c === '"') quoted = false
    } else if (c === '"') {
      quoted = true
    } else if (syntax === 'x86' ? c === '#' : c === ';' || (c === '/' && line[i + 1] === '/')) {
      return i
    }
  }
  return line.length
}

/**
 * 비교에 쓸 줄을 만든다. 레지스터를 <크기#번호>로 바꾸고 줄 끝 주석을 뺀다.
 * syntax가 null이면(레지스터를 찾지 못한 어셈블리) 줄을 그대로 돌려준다.
 *
 *   x86:   movsd (%rdi,%rcx,8), %xmm2   # xmm2 = mem[0],zero  →  movsd (<r64#0>,<r64#1>,8), <xmm#2>
 *   arm64: add x8, x8, w2, sxtw                               →  add <x#0>, <x#0>, <w#1>, sxtw
 */
export function normalizeRegisters(line, syntax) {
  if (syntax !== 'x86' && syntax !== 'arm64') return line
  const code = line.slice(0, commentStart(line, syntax))
  if (code.trim() === '') return line // 주석만 있는 줄 (또는 빈 줄)

  const numbers = new Map() // 물리 레지스터 → 이 줄에서 처음 나온 순서
  const rename = (register) => {
    let number = numbers.get(register.family)
    if (number === undefined) {
      number = numbers.size
      numbers.set(register.family, number)
    }
    return `<${register.kind}#${number}>`
  }
  const replace =
    syntax === 'x86'
      ? (text) =>
          text.replace(X86_REGISTER, (token, name) => {
            const register = X86_REGISTERS.get(name)
            return register ? rename(register) : token
          })
      : (text) =>
          text.replace(ARM64_REGISTER, (token, letter, number) => {
            const register = arm64Register(letter, number)
            return register ? rename(register) : token
          })

  return code
    .trimEnd()
    .split(QUOTED)
    .map((part, i) => (i % 2 === 1 ? part : replace(part)))
    .join('')
}
