package com.compareopt.optvisualizer.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * cargo 없이 rustc를 직접 부르는 명령줄. 그래서 단일 파일과 표준 라이브러리만 쓸 수 있다.
 *
 * GCC식 컴파일러와 달리 rustc는 크레이트 종류에 따라 결과가 크게 달라서, 코드를 보고 종류를 고른다 (forSource).
 * - fn main이 있으면 프로그램(bin)으로 컴파일한다. 라이브러리로 컴파일하면 main이 쓰이지 않는 비공개 함수가 되어
 *   어셈블리가 통째로 비어버린다.
 * - 없으면 라이브러리(lib)로 컴파일한다. 프로그램으로 컴파일하면 main이 없다는 에러(E0601)가 난다.
 *   라이브러리에서는 pub 함수만 결과에 남는다.
 */
final class RustcToolchain implements Toolchain {

    private static final List<OptLevel> LEVELS = List.of(
            level("0", "최적화 없음. cargo build의 기본값입니다. 소스 코드와 어셈블리가 가깝게 대응해서 디버깅하기 쉽고, "
                    + "정수 오버플로 검사도 켜져 있어서 오버플로가 나면 패닉으로 멈춥니다."),
            level("1", "기본적인 최적화. 오버플로 검사가 빠지고(값이 조용히 되감깁니다), 간단한 인라이닝과 죽은 코드 제거를 "
                    + "적용합니다."),
            level("2", "대부분의 최적화를 적용합니다. 반복자 같은 추상화가 이 단계에서 대부분 사라집니다."),
            level("3", "cargo build --release의 기본값입니다. 2에 더 공격적인 인라이닝과 루프 벡터화를 더합니다. "
                    + "코드에 따라 2보다 항상 빠른 것은 아닙니다."),
            level("s", "실행 속도보다 코드 크기를 줄이는 쪽을 우선합니다."),
            level("z", "s보다 크기를 더 줄입니다. 루프 벡터화도 끕니다."));

    private static final List<String> COMMON_ARGS = List.of(
            // 지정하지 않으면 2015로 컴파일된다. 2024는 붙여 넣는 코드에 흔한 #[no_mangle]을 에러로 처리하고,
            // 2021은 배포판 패키지의 오래된 rustc에서도 동작한다.
            "--edition=2021",
            // 기본값이 rustc 버전마다 달라서, 어디서든 같은 형식의 심볼이 나오도록 고정한다.
            "-C", "symbol-mangling-version=v0",
            // 실행 시간을 재는 프로그램도 화면에 보여주는 어셈블리와 같은 방식으로 만든다.
            "-C", "codegen-units=1");

    private static final Pattern COMMENT = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bfn\\s+main\\s*\\(");
    // 함수가 있으면 나오는 지시어. Windows(COFF)의 작은 함수에는 .cfi_startproc도 .seh_proc도 없어서
    // 함수 심볼에 항상 붙는 .type 32;로 찾는다.
    private static final Pattern FUNCTION_DIRECTIVE = Pattern.compile("\\.cfi_startproc|\\.type\\s+32;");
    private static final String NO_MAIN_ERROR = "[E0601]";

    private static final String EMPTY_ASSEMBLY_NOTICE =
            "어셈블리에 함수가 없습니다. fn main이 없는 코드는 라이브러리로 컴파일하는데, rustc는 라이브러리에서 쓰이지 않는 "
                    + "비공개 함수, 어떤 타입으로도 쓰이지 않은 제네릭 함수, #[inline]이 붙은 함수를 만들지 않습니다. "
                    + "fn main에서 호출하거나, 비공개 함수는 pub fn으로 바꾸고 #[inline]은 지워 보세요.";

    private final boolean program;

    RustcToolchain() {
        this(false);
    }

    private RustcToolchain(boolean program) {
        this.program = program;
    }

    @Override
    public List<OptLevel> levels() {
        return LEVELS;
    }

    @Override
    public List<String> versionArgs() {
        return List.of("--version");
    }

    @Override
    public Optional<String> versionMismatch(String versionOutput) {
        if (versionOutput.contains("rustc ")) {
            return Optional.empty();
        }
        return Optional.of("rustc가 아닌 것으로 보입니다.");
    }

    @Override
    public Toolchain forSource(String sourceCode) {
        return new RustcToolchain(hasMainFunction(sourceCode));
    }

    /** 메서드나 문자열, 모듈 안의 fn main을 프로그램의 진입점으로 잘못 찾으면 rustc가 E0601을 내므로 라이브러리로 다시 컴파일한다. */
    @Override
    public Optional<Toolchain> correctedBy(String compilerOutput) {
        if (program && compilerOutput.contains(NO_MAIN_ERROR)) {
            return Optional.of(new RustcToolchain(false));
        }
        return Optional.empty();
    }

    /**
     * 주석을 지운 코드에서 fn main을 찾는다. 문법을 해석하지 않으므로 틀릴 수 있지만 결과는 안전하다:
     * 잘못 찾으면 correctedBy가 바로잡고, 매크로가 만드는 main처럼 놓치면 어셈블리가 비어서 안내 문구가 나온다.
     */
    private static boolean hasMainFunction(String sourceCode) {
        return MAIN_FUNCTION.matcher(COMMENT.matcher(sourceCode).replaceAll(" ")).find();
    }

    /**
     * rustc --edition=2021 ... -C opt-level={n} --crate-type=lib -C incremental=incr-{n}
     *       --emit=asm=out.s,obj=out.o input.rs
     *
     * 한 번의 실행으로 둘 다 만드는 편이 따로 실행할 때보다 40%쯤 빠르고, 어셈블리와 크기가 같은 컴파일의 결과가 된다.
     * --emit은 링크하지 않으므로 프로그램으로 컴파일해도 링커가 필요 없다.
     *
     * 라이브러리의 -C incremental은 증분 빌드가 목적이 아니다. rustc는 작은 pub fn을 쓰는 쪽 크레이트에서
     * 인라인하려고 미뤄서 O1부터 어셈블리에서 지워버리는데, 증분 빌드에서는 이 판단을 하지 않는다.
     * 공식 동작이 아니라서 CompilerServiceTest가 실제 rustc로 이 효과를 확인한다.
     */
    @Override
    public List<List<String>> codegenCommands(OptLevel level, String sourceFile, String asmFile, String objFile) {
        List<String> args = commonArgs(level);
        if (program) {
            args.add("--crate-type=bin");
        } else {
            args.addAll(List.of("--crate-type=lib", "-C", "incremental=incr-" + level.id()));
        }
        args.addAll(List.of("--emit=asm=" + asmFile + ",obj=" + objFile, sourceFile));
        return List.of(args);
    }

    /** rustc --edition=2021 ... -C opt-level={n} -o out.exe input.rs — 항상 프로그램으로 빌드하므로 main이 있어야 성공한다. */
    @Override
    public List<String> executableArgs(OptLevel level, String sourceFile, String outputFile) {
        List<String> args = commonArgs(level);
        args.addAll(List.of("-o", outputFile, sourceFile));
        return args;
    }

    /**
     * Rust의 main은 값을 돌려주지 않으므로 0이 아닌 종료 코드는 대개 패닉(101)이다.
     * 오버플로 검사는 레벨 0에서만 켜져 있어서, 같은 코드가 레벨 0에서만 패닉으로 바로 끝나
     * 다른 레벨보다 훨씬 빠른 것처럼 측정될 수 있다.
     */
    @Override
    public Optional<String> executionFailure(int exitCode) {
        if (exitCode == 0) {
            return Optional.empty();
        }
        return Optional.of("프로그램이 도중에 끝나서(종료 코드 " + exitCode + ") 실행 시간을 잴 수 없습니다. "
                + "opt-level=0에서만 그렇다면 정수 오버플로로 패닉이 난 것일 수 있습니다.");
    }

    @Override
    public Optional<String> assemblyNotice(String assembly) {
        if (FUNCTION_DIRECTIVE.matcher(assembly).find()) {
            return Optional.empty();
        }
        return Optional.of(EMPTY_ASSEMBLY_NOTICE);
    }

    private static List<String> commonArgs(OptLevel level) {
        List<String> args = new ArrayList<>(COMMON_ARGS);
        args.addAll(List.of("-C", "opt-level=" + level.id()));
        return args;
    }

    private static OptLevel level(String id, String description) {
        return new OptLevel(id, "opt-level=" + id, description);
    }
}
