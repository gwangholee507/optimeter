import { describe, it, expect } from 'vitest'
import { detectRegisterSyntax, normalizeRegisters } from './registers'

const x86 = (line) => normalizeRegisters(line, 'x86')
const arm64 = (line) => normalizeRegisters(line, 'arm64')

describe('detectRegisterSyntax', () => {
  it('%가 붙은 레지스터가 있으면 x86이다', () => {
    expect(detectRegisterSyntax(['scale:', '\ttestl\t%esi, %esi', '\tretq'])).toBe('x86')
  })

  it('프레임 포인터만 써도 x86이다', () => {
    expect(detectRegisterSyntax(['\tpushq\t%rbp', '\tretq'])).toBe('x86')
  })

  it('맨 이름의 arm64 레지스터가 있으면 arm64다', () => {
    expect(detectRegisterSyntax(['_scale:', '\tcmp\tw1, #1', '\tret'])).toBe('arm64')
  })

  it('x86의 기호 이름이 arm64 레지스터처럼 생겨도 %레지스터가 있으면 x86이다', () => {
    expect(detectRegisterSyntax(['\tmovl\ts1(%rip), %eax'])).toBe('x86')
  })

  it('레지스터가 없으면 null', () => {
    expect(detectRegisterSyntax(['f:', '\tret', '\t.asciz\t"%d x1"'])).toBeNull()
  })
})

describe('normalizeRegisters (x86, AT&T 문법)', () => {
  it('레지스터 이름만 다른 줄은 같아진다', () => {
    expect(x86('\taddq\t%rdi, %rax')).toBe(x86('\taddq\t%rcx, %rdx'))
    expect(x86('\taddq\t%rdi, %rax')).toBe('\taddq\t<r64#0>, <r64#1>')
  })

  it('같은 레지스터를 두 번 쓰는지는 남긴다 (0으로 만드는 관용구)', () => {
    expect(x86('\txorl\t%eax, %eax')).not.toBe(x86('\txorl\t%ecx, %edx'))
    expect(x86('\txorl\t%eax, %eax')).toBe(x86('\txorl\t%ecx, %ecx'))
  })

  it('크기가 다르면 다르다', () => {
    expect(x86('\tmovl\t%eax, %ecx')).not.toBe(x86('\tmovl\t%rax, %rcx'))
    expect(x86('\tmovb\t%al, %cl')).toBe('\tmovb\t<r8#0>, <r8#1>')
    expect(x86('\tmovw\t%r8w, %r9w')).toBe('\tmovw\t<r16#0>, <r16#1>')
  })

  it('같은 물리 레지스터의 다른 크기는 같은 번호를 받는다', () => {
    expect(x86('\tmovslq\t%eax, %rax')).toBe('\tmovslq\t<r32#0>, <r64#0>')
    expect(x86('\tmovslq\t%edi, %rax')).toBe('\tmovslq\t<r32#0>, <r64#1>')
    expect(x86('\tmovslq\t%r8d, %r8')).toBe(x86('\tmovslq\t%ecx, %rcx'))
  })

  it('메모리 피연산자 안의 레지스터도 바꾼다', () => {
    expect(x86('\tmovsd\t(%rdi,%rcx,8), %xmm2')).toBe(x86('\tmovsd\t(%rcx,%rdx,8), %xmm1'))
  })

  it('벡터 레지스터는 xmm/ymm/zmm을 구분하고, 마스크 레지스터도 바꾼다', () => {
    expect(x86('\tvaddps\t%ymm0, %ymm1, %ymm2')).toBe('\tvaddps\t<ymm#0>, <ymm#1>, <ymm#2>')
    expect(x86('\tvaddps\t%ymm0, %ymm1, %ymm2')).not.toBe(x86('\tvaddps\t%xmm0, %xmm1, %xmm2'))
    expect(x86('\tvaddps\t%zmm0, %zmm1, %zmm2 {%k1}')).toBe(x86('\tvaddps\t%zmm3, %zmm4, %zmm5 {%k2}'))
  })

  it('스택 포인터, 프레임 포인터, 명령어 포인터는 그대로 둔다', () => {
    expect(x86('\tmovq\t%rdi, -8(%rbp)')).toBe('\tmovq\t<r64#0>, -8(%rbp)')
    expect(x86('\tmovq\t%rsp, %rbp')).toBe('\tmovq\t%rsp, %rbp')
    expect(x86('\tmovl\t%eax, g(%rip)')).toBe('\tmovl\t<r32#0>, g(%rip)')
    expect(x86('\tmovq\t%rdi, -8(%rbp)')).not.toBe(x86('\tmovq\t%rdi, -8(%rcx)'))
  })

  it('줄 끝 주석은 뺀다', () => {
    expect(x86('\tmovsd\t(%rdi), %xmm2            # xmm2 = mem[0],zero')).toBe(x86('\tmovsd\t(%rdi), %xmm1'))
    expect(x86('\tandl\t$2147483644, %edx               # imm = 0x7FFFFFFC')).toBe('\tandl\t$2147483644, <r32#0>')
  })

  it('주석만 있는 줄은 그대로 둔다', () => {
    const kill = '                                        # kill: def $eax killed $eax killed $rax'
    expect(x86(kill)).toBe(kill)
    expect(x86('# %bb.0:')).toBe('# %bb.0:')
  })

  it('따옴표 안은 바꾸지 않는다', () => {
    expect(x86('\t.asciz\t"%eax # %ecx"')).toBe('\t.asciz\t"%eax # %ecx"')
    expect(x86('\t.asciz\t"a\\"b" # 주석')).toBe('\t.asciz\t"a\\"b"')
  })

  it('레지스터가 아닌 %는 그대로 둔다 (x87 스택, 세그먼트)', () => {
    expect(x86('\tfldt\t%st(1)')).toBe('\tfldt\t%st(1)')
    expect(x86('\tmovq\t%fs:0, %rax')).toBe('\tmovq\t%fs:0, <r64#0>')
  })
})

describe('normalizeRegisters (arm64)', () => {
  it('레지스터 이름만 다른 줄은 같아진다', () => {
    expect(arm64('\tmul\tw0, w8, w9')).toBe(arm64('\tmul\tw8, w0, w1'))
    expect(arm64('\tadd\tx8, x8, w2, sxtw')).toBe('\tadd\t<x#0>, <x#0>, <w#1>, sxtw')
  })

  it('같은 레지스터를 여러 번 쓰는지와 크기는 남긴다', () => {
    expect(arm64('\tmul\tw0, w0, w0')).not.toBe(arm64('\tmul\tw0, w8, w9'))
    expect(arm64('\tadd\tw0, w1, w2')).not.toBe(arm64('\tadd\tx0, x1, x2'))
    expect(arm64('\tsxtw\tx9, w9')).not.toBe(arm64('\tsxtw\tx9, w3'))
  })

  it('부동소수점·벡터 레지스터와 배치·원소 표기', () => {
    expect(arm64('\tfmul.2d\tv2, v2, v0[0]')).toBe('\tfmul.2d\t<v#0>, <v#0>, <v#1>[0]')
    expect(arm64('\tfmul\tv2.2d, v2.2d, v0.d[0]')).toBe('\tfmul\t<v#0>.2d, <v#0>.2d, <v#1>.d[0]')
    expect(arm64('\tldp\tq2, q3, [x10, #-32]')).toBe(arm64('\tldp\tq4, q5, [x11, #-32]'))
    expect(arm64('\tfadd\td1, d1, d2')).not.toBe(arm64('\tfadd\ts1, s1, s2'))
  })

  it('sp, 영 레지스터, 프레임 포인터, 링크 레지스터는 그대로 둔다', () => {
    expect(arm64('\tstp\tx29, x30, [sp, #16]')).toBe('\tstp\tx29, x30, [sp, #16]')
    expect(arm64('\tstp\tx29, x30, [sp, #16]')).not.toBe(arm64('\tstp\tx19, x20, [sp, #16]'))
    expect(arm64('\tmov\tx9, xzr')).toBe('\tmov\t<x#0>, xzr')
  })

  it('레지스터처럼 생긴 기호 이름의 일부는 바꾸지 않는다', () => {
    expect(arm64('\tb.lt\tLBB0_3')).toBe('\tb.lt\tLBB0_3')
    expect(arm64('\tb\t.LBB0_7')).toBe('\tb\t.LBB0_7')
    expect(arm64('\tadrp\tx1, _d1@PAGE')).toBe('\tadrp\t<x#0>, _d1@PAGE')
    expect(arm64('\tadd\tx0, x0, :lo12:s1')).toBe('\tadd\t<x#0>, <x#0>, :lo12:s1')
    expect(arm64('\tand\tx9, x8, #0xd1')).toBe('\tand\t<x#0>, <x#1>, #0xd1')
    expect(arm64('s1:')).toBe('s1:')
  })

  it('줄 끝 주석(; 또는 //)은 빼고, 주석만 있는 줄은 그대로 둔다', () => {
    expect(arm64('\tmov\tx9, #0                          ; =0x0')).toBe('\tmov\t<x#0>, #0')
    expect(arm64('.LBB0_5:                                // =>This Inner Loop Header: Depth=1')).toBe('.LBB0_5:')
    const kill = '                                        ; kill: def $d0 killed $d0 def $q0'
    expect(arm64(kill)).toBe(kill)
  })

  it('#은 즉시값이라 주석으로 보지 않는다', () => {
    expect(arm64('\tcmp\tw1, #8')).toBe('\tcmp\t<w#0>, #8')
  })
})

describe('normalizeRegisters (문법을 모를 때)', () => {
  it('줄을 그대로 돌려준다', () => {
    expect(normalizeRegisters('\tmov\tw0, w1 ; 주석', null)).toBe('\tmov\tw0, w1 ; 주석')
  })
})
