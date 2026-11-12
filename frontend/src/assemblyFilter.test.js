import { describe, it, expect } from 'vitest'
import { filterAssembly } from './assemblyFilter'

const asm = (...lines) => lines.join('\n')

describe('filterAssembly', () => {
  it('macOS arm64: 지시어, 주석 줄, 지시어에서만 쓰는 레이블을 숨기고 명령어와 쓰이는 레이블은 남긴다', () => {
    // clang -O2 -S (Apple Silicon) 출력을 줄인 것
    const input = asm(
      '\t.build_version macos, 27, 0\tsdk_version 27, 0',
      '\t.section\t__TEXT,__text,regular,pure_instructions',
      '\t.globl\t_main                           ; -- Begin function main',
      '\t.p2align\t2',
      '_main:                                  ; @main',
      '\t.cfi_startproc',
      '; %bb.0:',
      'Lloh0:',
      '\tadrp\tx8, _counter@PAGE',
      '\tb.lt\tLBB2_2',
      'LBB2_2:',
      '\tadrp\tx0, l_.str@PAGE',
      '\tret',
      '\t.loh AdrpLdr\tLloh0, Lloh1',
      '\t.cfi_endproc',
      '                                        ; -- End function',
      '\t.section\t__DATA,__data',
      '\t.globl\t_counter                        ; @counter',
      '_counter:',
      '\t.long\t3                               ; 0x3',
      '',
      '\t.section\t__TEXT,__cstring,cstring_literals',
      'l_.str:                                 ; @.str',
      '\t.asciz\t"%d\\n"',
      '',
      '.subsections_via_symbols',
    )

    expect(filterAssembly(input)).toBe(
      asm(
        '_main:                                  ; @main',
        '\tadrp\tx8, _counter@PAGE',
        '\tb.lt\tLBB2_2',
        'LBB2_2:',
        '\tadrp\tx0, l_.str@PAGE',
        '\tret',
        '_counter:',
        '\t.long\t3                               ; 0x3',
        'l_.str:                                 ; @.str',
        '\t.asciz\t"%d\\n"',
      ),
    )
  })

  it('macOS: 아무도 쓰지 않는 내부 레이블에 딸린 데이터는 레이블과 함께 숨긴다', () => {
    const input = asm(
      '_f:',
      '\tret',
      '\t.section\t__TEXT,__const',
      'l_unused:',
      '\t.asciz\t"never read"',
      '.subsections_via_symbols',
    )

    expect(filterAssembly(input)).toBe(asm('_f:', '\tret'))
  })

  it('macOS: 예외 처리 표 섹션은 내용을 통째로 숨긴다', () => {
    const input = asm(
      '__RNvCs1_5input4main:',
      '\tbl\t_foo',
      '\t.cfi_lsda 16, Lexception0',
      '\t.section\t__TEXT,__gcc_except_tab',
      'GCC_except_table0:',
      'Lexception0:',
      '\t.byte\t255',
      '\t.uleb128 Lcst_end0-Lcst_begin0',
      '\t.section\t__TEXT,__text,regular,pure_instructions',
      '\tret',
      '.subsections_via_symbols',
    )

    expect(filterAssembly(input)).toBe(asm('__RNvCs1_5input4main:', '\tbl\t_foo', '\tret'))
  })

  it('Linux x86-64: .size에서만 쓰는 .Lfunc_end를 숨기고, 데이터가 가리키는 문자열은 남긴다', () => {
    // clang --target=x86_64-linux-gnu -O1 -S 출력을 줄인 것
    const input = asm(
      '\t.file\t"input.c"',
      '\t.text',
      '\t.globl\tmain                            # -- Begin function main',
      '\t.type\tmain,@function',
      'main:                                   # @main',
      '\t.cfi_startproc',
      '# %bb.0:',
      '\tjle\t.LBB2_1',
      '.LBB2_1:',
      '\tleaq\tnames.rel(%rip), %rcx',
      '\tretq',
      '.Lfunc_end2:',
      '\t.size\tmain, .Lfunc_end2-main',
      '\t.cfi_endproc',
      '\t.section\t.rodata,"a",@progbits',
      'names.rel:',
      '\t.long\t.L.str-names.rel',
      '\t.section\t.rodata.str1.1,"aMS",@progbits,1',
      '.L.str:',
      '\t.asciz\t"a"',
      '\t.ident\t"clang version 21.0.0"',
      '\t.section\t".note.GNU-stack","",@progbits',
      '\t.addrsig',
    )

    expect(filterAssembly(input)).toBe(
      asm(
        'main:                                   # @main',
        '\tjle\t.LBB2_1',
        '.LBB2_1:',
        '\tleaq\tnames.rel(%rip), %rcx',
        '\tretq',
        'names.rel:',
        '\t.long\t.L.str-names.rel',
        '.L.str:',
        '\t.asciz\t"a"',
      ),
    )
  })

  it('Linux: L로 시작하는 사용자 함수 이름은 쓰이지 않아도 숨기지 않는다', () => {
    const input = asm('\t.text', 'Lerp:', '\tret', 'lerp:', '\tret')

    expect(filterAssembly(input)).toBe(asm('Lerp:', '\tret', 'lerp:', '\tret'))
  })

  it('Windows: .def 줄, 기호 정의, .seh_ 지시어, 디버그 섹션을 숨기고 따옴표로 감싼 레이블은 남긴다', () => {
    // clang --target=x86_64-pc-windows-msvc -O1 -S 출력을 줄인 것
    const input = asm(
      '\t.def\t@feat.00;',
      '\t.scl\t3;',
      '\t.endef',
      '@feat.00 = 0',
      '\t.def\tmain;',
      '\t.scl\t2;',
      '\t.type\t32;',
      '\t.endef',
      '\t.text',
      'main:                                   # @main',
      '.seh_proc main',
      '\tsubq\t$40, %rsp',
      '\t.seh_stackalloc 40',
      '\t.seh_endprologue',
      '\tleaq\tnames(%rip), %rcx',
      '\tretq',
      '\t.seh_endproc',
      '\t.section\t.rdata,"dr"',
      'names:',
      '\t.quad\t"??_C@_01MCMALHOG@a?$AA@"',
      '\t.section\t.rdata,"dr",discard,"??_C@_01MCMALHOG@a?$AA@"',
      '"??_C@_01MCMALHOG@a?$AA@":',
      '\t.asciz\t"a"',
      '\t.section\t.debug$S,"dr"',
      '\t.long\t4                               # Debug section magic',
      '.Ltmp0:',
      '\t.short\t4353                            # Record kind: S_OBJNAME',
      '\t.asciz\t"clang version 21.0.0"',
      '\t.addrsig',
    )

    expect(filterAssembly(input)).toBe(
      asm(
        'main:                                   # @main',
        '\tsubq\t$40, %rsp',
        '\tleaq\tnames(%rip), %rcx',
        '\tretq',
        'names:',
        '\t.quad\t"??_C@_01MCMALHOG@a?$AA@"',
        '"??_C@_01MCMALHOG@a?$AA@":',
        '\t.asciz\t"a"',
      ),
    )
  })

  it('Linux arm64: // 주석 줄을 숨긴다', () => {
    const input = asm('square:', '// %bb.0:', '\tmul\tw0, w0, w0', '\tret')

    expect(filterAssembly(input)).toBe(asm('square:', '\tmul\tw0, w0, w0', '\tret'))
  })

  it('레이블과 명령어를 한 줄에 쓴 경우는 명령어로 보고 남긴다', () => {
    const input = asm('\t.text', '.Lunused: nop', '\tret')

    expect(filterAssembly(input)).toBe(asm('.Lunused: nop', '\tret'))
  })

  it('레이블 없이 변수를 정의하는 .comm과 .zerofill은 남긴다', () => {
    const input = asm(
      '\t.comm\tbuffer,400,16',
      '.zerofill __DATA,__bss,_table,400,2          ; @table',
      '.subsections_via_symbols',
    )

    expect(filterAssembly(input)).toBe(
      asm('\t.comm\tbuffer,400,16', '.zerofill __DATA,__bss,_table,400,2          ; @table'),
    )
  })

  it('.pushsection으로 들어간 디버그 섹션은 .popsection에서 끝난다', () => {
    const input = asm(
      '\t.text',
      'f:',
      '\t.pushsection\t.debug_line,"",@progbits',
      '\t.byte\t1',
      '\t.popsection',
      '\tret',
    )

    expect(filterAssembly(input)).toBe(asm('f:', '\tret'))
  })

  it('빈 어셈블리는 빈 문자열을 돌려준다', () => {
    expect(filterAssembly('')).toBe('')
  })
})
