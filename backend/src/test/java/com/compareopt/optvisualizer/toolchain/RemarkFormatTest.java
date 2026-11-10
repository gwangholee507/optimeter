package com.compareopt.optvisualizer.toolchain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.compareopt.optvisualizer.toolchain.OptRemark.Kind.APPLIED;
import static com.compareopt.optvisualizer.toolchain.OptRemark.Kind.MISSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 컴파일러를 실행하지 않고, 실제 컴파일러(Apple clang 21, GCC 16)가 낸 출력을 그대로 넣어 읽는 방법만 검증한다.
 * 실제 컴파일러가 이 형식으로 출력하는지는 CompilerServiceTest에서 확인한다.
 */
class RemarkFormatTest {

    @Test
    void clang의_remark에서_위치와_패스_이름과_적용_여부를_읽는다() {
        String output = """
                input.c:10:18: remark: 'square' inlined into 'sum_squares' with (cost=-15030, threshold=337) at callsite sum_squares:3:18; [-Rpass=inline]
                   10 |         total += square(a[i]);
                      |                  ^
                input.c:9:5: remark: loop not vectorized [-Rpass-missed=loop-vectorize]
                    9 |     for (int i = 0; i < n; i++) {
                      |     ^
                """;

        List<OptRemark> remarks = RemarkFormat.CLANG.parse(output, "input.c");

        // 소스 인용 줄은 버리고, 소스 줄 순서로 돌려준다
        assertThat(remarks).extracting(OptRemark::kind, OptRemark::line, OptRemark::column, OptRemark::pass)
                .containsExactly(
                        tuple(MISSED, 9, 5, "loop-vectorize"),
                        tuple(APPLIED, 10, 18, "inline"));
        assertThat(remarks.get(0).message()).isEqualTo("loop not vectorized");
        assertThat(remarks.get(1).message()).isEqualTo(
                "'square' inlined into 'sum_squares' with (cost=-15030, threshold=337) at callsite sum_squares:3:18;");
    }

    @Test
    void clang의_패스_이름으로_최적화_종류의_이름과_설명을_붙인다() {
        String output = """
                input.c:2:59: remark: transforming tail recursion into loop [-Rpass=tailcallelim]
                input.c:3:62: remark: Transformed loop-strided store in zero function into a call to llvm.memset.p0.i64() intrinsic [-Rpass=loop-idiom]
                input.c:4:88: remark: hoisting load [-Rpass=licm]
                input.c:5:1: remark: something this project has never heard of [-Rpass=brand-new-pass]
                """;

        List<OptRemark> remarks = RemarkFormat.CLANG.parse(output, "input.c");

        assertThat(remarks).extracting(OptRemark::topicLabel)
                .containsExactly("꼬리 재귀 제거", "루프 관용구 치환", "루프 불변 코드 이동", null);
        assertThat(remarks.subList(0, 3)).allSatisfy(r -> assertThat(r.topicDescription()).isNotBlank());
        // 모르는 패스도 버리지 않고 컴파일러의 원문 그대로 보여준다
        assertThat(remarks.get(3).pass()).isEqualTo("brand-new-pass");
        assertThat(remarks.get(3).topicDescription()).isNull();
    }

    @Test
    void clang의_코드_생성_기록은_최적화가_아니라서_버린다() {
        String output = """
                input.c:7:1: remark: 3 virtual registers copies 2.000000e+00 total copies cost generated in function [-Rpass-missed=regalloc]
                input.c:15:5: remark: Stack protection applied to function main due to a stack allocated buffer or struct containing a buffer [-Rpass=stack-protector]
                input.c:2:59: remark: fact has uninlinable pattern (recursive) and cost is not fully computed [-Rpass-missed=inline-cost]
                input.c:2:59: remark: 'fact' not inlined into 'fact' because it should never be inlined (cost=never): recursive [-Rpass-missed=inline]
                """;

        assertThat(RemarkFormat.CLANG.parse(output, "input.c")).extracting(OptRemark::pass)
                .containsExactly("inline");
    }

    @Test
    void 같은_위치의_같은_remark는_하나로_합치고_횟수를_센다() {
        String output = """
                input.c:9:5: remark: loop not vectorized [-Rpass-missed=loop-vectorize]
                input.c:17:5: remark: loop not vectorized [-Rpass-missed=loop-vectorize]
                input.c:9:5: remark: loop not vectorized [-Rpass-missed=loop-vectorize]
                """;

        assertThat(RemarkFormat.CLANG.parse(output, "input.c")).extracting(OptRemark::line, OptRemark::count)
                .containsExactly(tuple(9, 2), tuple(17, 1));
    }

    @Test
    void 헤더_파일_안의_remark는_버린다() {
        // C++ 표준 라이브러리를 쓰면 대부분이 헤더 내부의 기록이고, 화면에서 가리킬 사용자 코드의 줄이 없다
        String output = """
                /usr/include/c++/v1/__numeric/accumulate.h:28:3: remark: vectorized loop (vectorization width: 4, interleaved count: 4) [-Rpass=loop-vectorize]
                C:\\msys64\\ucrt64\\include\\c++\\15.1.0\\bits\\stl_numeric.h:139:22: remark: loop not vectorized [-Rpass-missed=loop-vectorize]
                input.cpp:3:47: remark: '_ZNSt3__110accumulate' inlined into '_Z5total' with (cost=-15, threshold=225) at callsite total:0:47; [-Rpass=inline]
                """;

        assertThat(RemarkFormat.CLANG.parse(output, "input.cpp")).extracting(OptRemark::line, OptRemark::pass)
                .containsExactly(tuple(3, "inline"));
    }

    @Test
    void 경고와_에러_줄은_remark가_아니다() {
        String output = """
                input.c:1:19: warning: unused variable 'unused' [-Wunused-variable]
                    1 | int f(int x){ int unused; return x*2; }
                      |                   ^
                1 warning generated.
                """;

        assertThat(RemarkFormat.CLANG.parse(output, "input.c")).isEmpty();
        assertThat(RemarkFormat.GCC.parse(output, "input.c")).isEmpty();
    }

    @Test
    void gcc의_remark에서_위치와_적용_여부를_읽고_메시지로_최적화_종류를_찾는다() {
        String output = """
                input.c:10:18: optimized:  Inlining square/2 into sum_squares/3.
                input.c:18:5: missed:   not inlinable: main/4 -> printf/5, function body not available
                input.c:9:23: missed: couldn't vectorize loop
                input.c:9:23: missed: Loop costings not worthwhile.
                input.c:9:23: optimized: loop vectorized using 16 byte vectors and unroll factor 4
                input.c:9:23: optimized: loop with 2 iterations completely unrolled (header execution count 36207767)
                input.c:3:47: optimized: Loop 1 distributed: split to 0 loops and 1 library calls.
                input.c:18:5: missed: statement clobbers memory: printf ("%d\\n", _36);
                """;

        List<OptRemark> remarks = RemarkFormat.GCC.parse(output, "input.c");

        // 같은 위치의 remark끼리는 컴파일러가 낸 순서를 유지한다
        assertThat(remarks).extracting(OptRemark::kind, OptRemark::line, OptRemark::topicLabel).containsExactly(
                tuple(APPLIED, 3, "루프 관용구 치환"),
                tuple(MISSED, 9, "루프 벡터화"),
                tuple(MISSED, 9, null),
                tuple(APPLIED, 9, "루프 벡터화"),
                tuple(APPLIED, 9, "루프 펼치기"),
                tuple(APPLIED, 10, "함수 인라이닝"),
                tuple(MISSED, 18, "함수 인라이닝"),
                tuple(MISSED, 18, null));
        // GCC는 패스 이름을 알려주지 않고, 메시지 앞의 들여쓰기는 떼어낸다
        assertThat(remarks).allSatisfy(r -> assertThat(r.pass()).isNull());
        assertThat(remarks.get(5).message()).isEqualTo("Inlining square/2 into sum_squares/3.");
        assertThat(remarks.get(5).column()).isEqualTo(18);
    }

    @Test
    void gcc가_칸_번호_없이_줄_번호만_알려주면_칸은_0이다() {
        List<OptRemark> remarks = RemarkFormat.GCC.parse("input.c:9: missed: couldn't vectorize loop\n", "input.c");

        assertThat(remarks).extracting(OptRemark::line, OptRemark::column).containsExactly(tuple(9, 0));
    }
}
