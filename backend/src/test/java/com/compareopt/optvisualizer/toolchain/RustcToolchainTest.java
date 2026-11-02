package com.compareopt.optvisualizer.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컴파일러를 실행하지 않고 명령줄 조립과 판정만 검증한다.
 * 실제로 컴파일되는지는 CompilerServiceTest에서 확인한다.
 */
class RustcToolchainTest {

    private static final String LIBRARY = "pub fn add(a: i32, b: i32) -> i32 { a + b }";
    private static final String PROGRAM = "fn main() { println!(\"hi\"); }";

    private final Toolchain rustc = ToolchainKind.RUSTC.toolchain();

    @Test
    void 레벨은_0부터_3까지와_크기_최적화_s_z_순서다() {
        assertThat(rustc.levels()).extracting(OptLevel::id).containsExactly("0", "1", "2", "3", "s", "z");
        assertThat(rustc.levels()).extracting(OptLevel::label).containsExactly(
                "opt-level=0", "opt-level=1", "opt-level=2", "opt-level=3", "opt-level=s", "opt-level=z");
        assertThat(rustc.levels()).allSatisfy(level -> assertThat(level.description()).isNotBlank());
    }

    @Test
    void main이_없는_코드는_라이브러리로_컴파일한다() {
        OptLevel level2 = rustc.levels().get(2);

        // 어셈블리와 오브젝트 파일을 한 번의 실행으로 만든다
        assertThat(rustc.forSource(LIBRARY).codegenCommands(level2, "input.rs", "out-2.s", "out-2.o"))
                .containsExactly(List.of(
                        "--edition=2021", "-C", "symbol-mangling-version=v0", "-C", "codegen-units=1",
                        "-C", "opt-level=2", "--crate-type=lib", "-C", "incremental=incr-2",
                        "--emit=asm=out-2.s,obj=out-2.o", "input.rs"));
    }

    @Test
    void main이_있는_코드는_프로그램으로_컴파일한다() {
        OptLevel level3 = rustc.levels().get(3);

        assertThat(rustc.forSource(PROGRAM).codegenCommands(level3, "input.rs", "out-3.s", "out-3.o"))
                .containsExactly(List.of(
                        "--edition=2021", "-C", "symbol-mangling-version=v0", "-C", "codegen-units=1",
                        "-C", "opt-level=3", "--crate-type=bin",
                        "--emit=asm=out-3.s,obj=out-3.o", "input.rs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fn main() {}",
            "pub fn main() {}",
            "fn   main  () {}",
            "fn\nmain() {}",
            "fn helper() {}\n\nfn main() -> Result<(), String> { Ok(()) }",
    })
    void fn_main을_찾으면_프로그램이다(String code) {
        assertThat(commandOf(code)).contains("--crate-type=bin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pub fn add() {}",
            "pub fn main_loop() {}",
            "pub fn domain() {}",
            "// fn main() {}\npub fn add() {}",
            "/* fn main() {}\n */ pub fn add() {}",
    })
    void 주석이나_비슷한_이름은_main으로_치지_않는다(String code) {
        assertThat(commandOf(code)).contains("--crate-type=lib");
    }

    @Test
    void 실행_파일은_코드와_상관없이_같은_방식으로_만든다() {
        OptLevel level2 = rustc.levels().get(2);
        List<String> expected = List.of(
                "--edition=2021", "-C", "symbol-mangling-version=v0", "-C", "codegen-units=1",
                "-C", "opt-level=2", "-o", "out-2.exe", "input.rs");

        assertThat(rustc.forSource(PROGRAM).executableArgs(level2, "input.rs", "out-2.exe")).isEqualTo(expected);
        assertThat(rustc.forSource(LIBRARY).executableArgs(level2, "input.rs", "out-2.exe")).isEqualTo(expected);
    }

    @Test
    void 설치_확인은_version_인자로_하고_rustc_출력만_인정한다() {
        assertThat(rustc.versionArgs()).containsExactly("--version");
        assertThat(rustc.versionMismatch("rustc 1.98.1 (48a229cea 2026-09-01)")).isEmpty();
        assertThat(rustc.versionMismatch("Apple clang version 17.0.0 (clang-1700.0.13.3)")).isPresent();
    }

    @Test
    void 종료_코드가_0이_아니면_실행_시간을_믿을_수_없다() {
        assertThat(rustc.executionFailure(0)).isEmpty();
        assertThat(rustc.executionFailure(101)).hasValueSatisfying(reason -> assertThat(reason).contains("101"));
    }

    @Test
    void 어셈블리에_함수가_없으면_안내_문구를_돌려준다() {
        // 아무것도 남지 않았을 때 macOS에서 실제로 나오는 어셈블리
        String empty = "\t.build_version macos, 11, 0\n\n.subsections_via_symbols\n";

        assertThat(rustc.assemblyNotice(empty)).hasValueSatisfying(notice -> assertThat(notice).contains("pub fn"));
        assertThat(rustc.assemblyNotice("add:\n\t.cfi_startproc\n\tret\n\t.cfi_endproc\n")).isEmpty();
        assertThat(rustc.assemblyNotice("add:\n.seh_proc add\n\tret\n.seh_endproc\n")).isEmpty();
    }

    private List<String> commandOf(String code) {
        return rustc.forSource(code).codegenCommands(rustc.levels().get(0), "input.rs", "out-0.s", "out-0.o").get(0);
    }
}
