package com.compareopt.optvisualizer.toolchain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컴파일러를 실행하지 않고 명령줄 조립과 버전 출력 판정만 검증한다.
 * 실제로 컴파일되는지는 CompilerServiceTest에서 확인한다.
 */
class GccStyleToolchainTest {

    private final Toolchain clang = ToolchainKind.CLANG.toolchain();
    private final Toolchain gcc = ToolchainKind.GCC.toolchain();

    @Test
    void 레벨은_O0부터_O3까지_순서대로다() {
        assertThat(clang.levels()).extracting(OptLevel::id).containsExactly("O0", "O1", "O2", "O3");
        assertThat(gcc.levels()).extracting(OptLevel::id).containsExactly("O0", "O1", "O2", "O3");
    }

    @Test
    void 레벨마다_화면에_보여줄_이름과_설명이_있다() {
        assertThat(clang.levels()).extracting(OptLevel::label).containsExactly("-O0", "-O1", "-O2", "-O3");
        assertThat(clang.levels()).allSatisfy(level -> assertThat(level.description()).isNotBlank());
    }

    @Test
    void 어셈블리_오브젝트_실행파일_인자를_GCC식으로_만든다() {
        OptLevel o2 = clang.levels().get(2);

        // 첫 번째 명령이 어셈블리, 두 번째 명령이 오브젝트 파일을 만든다
        assertThat(clang.codegenCommands(o2, "input.c", "out-O2.s", "out-O2.o")).containsExactly(
                List.of("-S", "-O2", "input.c", "-o", "out-O2.s"),
                List.of("-c", "-O2", "input.c", "-o", "out-O2.o"));
        assertThat(clang.executableArgs(o2, "input.cpp", "out-O2.exe"))
                .containsExactly("-O2", "input.cpp", "-o", "out-O2.exe");
    }

    @Test
    void 코드_모양과_종료_코드를_따지지_않고_안내_문구도_없다() {
        assertThat(clang.forSource("int main(void) { return 0; }")).isSameAs(clang);
        // C는 계산 결과를 main의 반환값으로 쓰는 코드가 흔해서 0이 아닌 종료 코드도 정상 실행으로 본다
        assertThat(clang.executionFailure(1)).isEmpty();
        assertThat(clang.assemblyNotice("")).isEmpty();
    }

    @Test
    void 설치_확인은_version_인자로_한다() {
        assertThat(clang.versionArgs()).containsExactly("--version");
        assertThat(gcc.versionArgs()).containsExactly("--version");
    }

    @Test
    void gcc_계열은_clang의_별칭을_GCC로_인정하지_않는다() {
        // macOS의 /usr/bin/gcc --version 출력
        String appleClang = "Apple clang version 17.0.0 (clang-1700.0.13.3)\nTarget: arm64-apple-darwin25.6.0";

        assertThat(gcc.versionMismatch(appleClang)).isPresent();
        assertThat(gcc.versionMismatch("gcc-16 (Homebrew GCC 16.1.0) 16.1.0")).isEmpty();
    }

    @Test
    void clang_계열은_clang_출력을_그대로_인정한다() {
        assertThat(clang.versionMismatch("Apple clang version 17.0.0 (clang-1700.0.13.3)")).isEmpty();
        assertThat(clang.versionMismatch("Ubuntu clang version 18.1.3")).isEmpty();
    }
}
