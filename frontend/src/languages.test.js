import { describe, it, expect } from 'vitest'
import {
  SAMPLE_CODE,
  defaultCompilerFor,
  groupByLanguage,
  initialLanguage,
  switchDraft,
} from './languages'

// /api/compilers 응답과 같은 모양
const compilers = [
  { id: 'clang', label: 'Clang (LLVM)', available: true, language: 'c', languageLabel: 'C' },
  { id: 'gcc', label: 'GNU GCC', available: false, language: 'c', languageLabel: 'C' },
  { id: 'clangxx', label: 'Clang++ (LLVM)', available: false, language: 'cpp', languageLabel: 'C++' },
  { id: 'gxx', label: 'GNU G++', available: true, language: 'cpp', languageLabel: 'C++' },
]

describe('groupByLanguage', () => {
  it('서버가 준 순서를 유지하며 언어별로 묶는다', () => {
    const languages = groupByLanguage(compilers)

    expect(languages.map((l) => l.id)).toEqual(['c', 'cpp'])
    expect(languages.map((l) => l.label)).toEqual(['C', 'C++'])
    expect(languages[1].compilers.map((c) => c.id)).toEqual(['clangxx', 'gxx'])
  })

  it('사용 가능한 컴파일러가 하나라도 있어야 그 언어를 사용 가능으로 본다', () => {
    const languages = groupByLanguage([
      ...compilers,
      { id: 'rustc', label: 'rustc', available: false, language: 'rust', languageLabel: 'Rust' },
    ])

    expect(languages.map((l) => l.available)).toEqual([true, true, false])
  })
})

describe('defaultCompilerFor / initialLanguage', () => {
  it('언어 안에서 사용 가능한 첫 컴파일러를 고른다', () => {
    const cpp = groupByLanguage(compilers)[1]

    expect(defaultCompilerFor(cpp).id).toBe('gxx')
  })

  it('사용 가능한 컴파일러가 없으면 첫 컴파일러를 고른다', () => {
    const [c] = groupByLanguage(compilers.map((c) => ({ ...c, available: false })))

    expect(defaultCompilerFor(c).id).toBe('clang')
  })

  it('처음에는 사용 가능한 컴파일러가 있는 첫 언어를 고른다', () => {
    const onlyCpp = compilers.map((c) => ({ ...c, available: c.language === 'cpp' }))

    expect(initialLanguage(groupByLanguage(compilers)).id).toBe('c')
    expect(initialLanguage(groupByLanguage(onlyCpp)).id).toBe('cpp')
  })
})

describe('switchDraft', () => {
  it('처음 가는 언어는 예제 코드를 넣고, 지금 코드는 원래 언어의 초안으로 보관한다', () => {
    const { drafts, code } = switchDraft({}, 'c', 'cpp', 'int edited;')

    expect(code).toBe(SAMPLE_CODE.cpp)
    expect(drafts.c).toBe('int edited;')
  })

  it('Rust로 처음 가면 Rust 예제를 넣는다', () => {
    const { code } = switchDraft({}, 'c', 'rust', 'int edited;')

    expect(code).toBe(SAMPLE_CODE.rust)
    expect(code).toContain('pub fn')
  })

  it('다시 돌아오면 보관해둔 초안을 복원한다', () => {
    const toCpp = switchDraft({}, 'c', 'cpp', 'int edited;')
    const backToC = switchDraft(toCpp.drafts, 'cpp', 'c', 'template <class T> T id(T x);')

    expect(backToC.code).toBe('int edited;')
    expect(backToC.drafts.cpp).toBe('template <class T> T id(T x);')
  })

  it('예제가 없는 언어는 빈 코드로 시작한다', () => {
    expect(switchDraft({}, 'c', 'zig', 'int x;').code).toBe('')
  })
})
