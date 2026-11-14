package com.compareopt.optvisualizer.service;

import com.compareopt.optvisualizer.config.CompilerProperties;
import com.compareopt.optvisualizer.dto.OptimizationResult;
import com.compareopt.optvisualizer.platform.ProcessResult;
import com.compareopt.optvisualizer.platform.ProcessRunner;
import com.compareopt.optvisualizer.toolchain.Language;
import com.compareopt.optvisualizer.toolchain.OptRemark;
import com.compareopt.optvisualizer.toolchain.ToolchainKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CompilerService는 실제 컴파일러를 서브프로세스로 호출하는 통합 성격의 테스트다.
 * (mock 없이 순수 단위 테스트로 만들기엔 핵심 로직이 전부 "외부 프로세스를 어떻게 다루는가"라서
 *  실제 컴파일 결과를 검증하는 게 더 의미 있다고 판단함)
 *
 * macOS/Linux/Windows 어디서든 clang이 설치되어 있어야 통과한다 (CI는 세 OS 모두에서 돌린다).
 * 그래서 검증 내용도 특정 OS의 경로나 심볼 표기에 기대지 않도록 작성한다.
 * gcc, C++(clangxx, gxx), rustc 테스트는 그 컴파일러가 없으면 건너뛴다 (macOS의 gcc/g++는 clang 별칭이라
 * toolchain: gcc가 걸러냄). 단, CI가 OPTVIS_REQUIRED_COMPILERS로 필수 지정한 컴파일러는 없으면 실패한다
 * (RequiredCompilers 참고).
 */
class CompilerServiceTest {

    // static이라 O2에서 반드시 인라인되고, 5번째 줄에 호출이 하나만 있어서 remark의 줄 번호를 확인할 수 있다
    private static final String INLINING_CODE = """
            static int square(int x) { return x * x; }
            int sum_loop(int n) {
                int total = 0;
                for (int i = 0; i < n; i++) {
                    total += square(i);
                }
                return total;
            }
            """;

    private CompilerService compilerService;
    private CompilerRegistry registry;

    @BeforeEach
    void setUp() {
        CompilerProperties properties = new CompilerProperties();
        properties.setOptions(List.of(
                option("clang", "Clang (LLVM)", Language.C, ToolchainKind.CLANG, "clang"),
                option("gcc", "GNU GCC", Language.C, ToolchainKind.GCC,
                        "gcc-16", "gcc-15", "gcc-14", "gcc-13", "gcc-12", "gcc"),
                option("clangxx", "Clang++ (LLVM)", Language.CPP, ToolchainKind.CLANG, "clang++"),
                option("gxx", "GNU G++", Language.CPP, ToolchainKind.GCC,
                        "g++-16", "g++-15", "g++-14", "g++-13", "g++-12", "g++"),
                option("rustc", "rustc (LLVM)", Language.RUST, ToolchainKind.RUSTC, "rustc")));
        ProcessRunner processRunner = new ProcessRunner();
        registry = new CompilerRegistry(properties, processRunner);
        compilerService = new CompilerService(registry, processRunner);
    }

    @Test
    void 정상적인_C코드는_O0부터_O3까지_모두_컴파일에_성공한다() {
        String code = "int square(int x) { return x * x; }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "clang", false);

        assertThat(results).hasSize(4);
        assertThat(results).extracting(OptimizationResult::getLevel)
                .containsExactly("O0", "O1", "O2", "O3");
        // 화면은 레벨 이름과 설명을 결과에서 그대로 가져다 쓴다
        assertThat(results).extracting(OptimizationResult::getLevelLabel)
                .containsExactly("-O0", "-O1", "-O2", "-O3");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getLevelDescription()).isNotBlank();
            assertThat(r.getCompilerId()).isEqualTo("clang");
            // macOS(Mach-O)는 심볼 앞에 '_'가 붙지만(_square) Linux(ELF)는 안 붙는다(square).
            // 플랫폼에 상관없이 통과해야 하므로 언더스코어 없는 쪽으로 검증한다.
            assertThat(r.getAssembly()).contains("square");
            // Windows의 컴파일러는 \r\n으로 쓰지만 응답에서는 \n으로 통일되어야 한다.
            assertThat(r.getAssembly()).doesNotContain("\r");
            assertThat(r.getBinarySizeBytes()).isGreaterThan(0);
            assertThat(r.getExecutionTimeMs()).isNull(); // runBenchmark=false
        });
    }

    @Test
    void 최적화가_적용되면_오브젝트_파일_크기가_O0보다_커지지_않는다() {
        String code = """
                int square(int x) { return x * x; }
                int sum_loop(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) { total += square(i); }
                    return total;
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "clang", false);
        long o0Size = results.get(0).getBinarySizeBytes();

        assertThat(results).allSatisfy(r ->
                assertThat(r.getBinarySizeBytes()).isLessThanOrEqualTo(o0Size)
        );
    }

    @Test
    void 문법_오류가_있는_코드는_모든_레벨에서_실패하고_에러메시지에_임시경로가_노출되지_않는다() {
        String brokenCode = "int f(int x) { retrun x + ; }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(brokenCode, "clang", false);

        assertThat(results).hasSize(4);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getErrorMessage()).contains("input.c");
            // 임시 작업 디렉터리 이름(opt-visualizer-XXXX)이 안 보이면 절대 경로가 노출되지 않은 것.
            // OS마다 임시 디렉터리 위치(/var/folders, /tmp, C:\Users\...\Temp)가 달라서 이름으로 확인한다.
            assertThat(r.getErrorMessage()).doesNotContain("opt-visualizer-");
        });
    }

    @Test
    void 경고가_아주_많이_출력돼도_컴파일이_멈추지_않는다() {
        // 컴파일러 출력을 파이프로 받으면 버퍼(수 KB~수십 KB)가 찼을 때 컴파일러가 멈춰서
        // 정상 코드가 "시간 초과"로 실패한다. 버퍼를 확실히 넘기는 양의 경고를 내보내 확인한다.
        String code = "#warning this line only exists to produce compiler output\n".repeat(1000)
                + "int square(int x) { return x * x; }\n";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "clang", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getAssembly()).contains("square");
        });
    }

    @Test
    void 존재하지_않는_컴파일러를_요청하면_예외가_발생한다() {
        assertThatThrownBy(() -> compilerService.compareOptimizationLevels("int f(){return 0;}", "msvc", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void main이_있고_runBenchmark가_true면_실행_시간이_측정된다() {
        assumeTrue(registry.resolve("clang").available());
        String code = """
                int add(int a, int b) { return a + b; }
                int main(void) { return add(1, 2); }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getExecutionTimeMs()).isNotNull();
            assertThat(r.getExecutionTimeMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(r.getExecutionError()).isNull();
        });
    }

    @Test
    void main이_없는_코드는_runBenchmark가_true여도_컴파일은_성공하고_실행만_건너뛴다() {
        String code = "int square(int x) { return x * x; }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue(); // 컴파일/어셈블리 자체는 성공
            assertThat(r.getExecutionTimeMs()).isNull();
            assertThat(r.getExecutionError()).contains("실행 파일을 만들 수 없습니다"); // 링크 실패 사유가 채워짐
        });
    }

    @Test
    void gcc가_설치되어_있으면_gcc로도_컴파일이_성공한다() {
        RequiredCompilers.assumeAvailable(registry, "gcc");
        String code = "int square(int x) { return x * x; }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "gcc", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getCompilerId()).isEqualTo("gcc");
            assertThat(r.getAssembly()).contains("square");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"clang", "gcc"})
    void 레벨마다_컴파일러가_남긴_최적화_remark가_담긴다(String compilerId) {
        RequiredCompilers.assumeAvailable(registry, compilerId);

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(INLINING_CODE, compilerId, false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            // remark가 없는 레벨도 null이 아니라 빈 목록이다 (화면이 "기록 없음"을 보여준다)
            assertThat(r.getRemarks()).isNotNull();
            assertThat(r.getOmittedRemarkCount()).isZero();
        });
        // O0는 인라이닝을 하지 않는다
        assertThat(results.get(0).getRemarks()).noneMatch(CompilerServiceTest::isAppliedInlining);
        // O2는 5번째 줄의 square(i) 호출을 인라인하고, 그 사실을 줄 번호와 함께 알려준다
        assertThat(results.get(2).getRemarks()).anySatisfy(remark -> {
            assertThat(isAppliedInlining(remark)).isTrue();
            assertThat(remark.line()).isEqualTo(5);
            assertThat(remark.topicDescription()).isNotBlank();
            assertThat(remark.message()).contains("square");
            assertThat(remark.count()).isPositive();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"clang", "gcc"})
    void remark를_내게_하는_인자는_어셈블리를_바꾸지_않는다(String compilerId, @TempDir Path tempDir) throws Exception {
        // 화면의 어셈블리(와 크기를 재는 오브젝트 파일)는 remark 인자를 붙인 명령으로 만든다. 인자 때문에 코드가
        // 달라지면 사용자가 같은 레벨로 직접 컴파일한 결과와 어긋나므로, 인자 없이 직접 컴파일한 결과와 같은지 확인한다.
        RequiredCompilers.assumeAvailable(registry, compilerId);
        CompilerRegistry.ResolvedCompiler compiler = registry.resolve(compilerId);
        Files.writeString(tempDir.resolve("input.c"), INLINING_CODE);

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(INLINING_CODE, compilerId, false);

        for (OptimizationResult result : results) {
            String asmName = "plain-" + result.getLevel() + ".s";
            ProcessResult plain = new ProcessRunner().run(
                    List.of(compiler.path(), "-S", "-" + result.getLevel(), "input.c", "-o", asmName),
                    tempDir, compiler.toolDir(), Duration.ofSeconds(60));
            assertThat(plain.succeeded()).as(plain.output()).isTrue();
            assertThat(result.getAssembly()).as(result.getLevelLabel())
                    .isEqualTo(ProcessRunner.normalizeNewlines(Files.readString(tempDir.resolve(asmName))));
        }
    }

    @Test
    void remark가_너무_많으면_앞에서부터_정해진_개수만_담고_나머지는_개수만_알려준다() {
        // 줄마다 square 호출이 하나씩 있어서 O2에서 인라이닝 remark가 줄 수만큼 나온다
        int callers = CompilerService.MAX_REMARKS_PER_LEVEL + 50;
        StringBuilder code = new StringBuilder("static int square(int x) { return x * x; }\n");
        for (int i = 0; i < callers; i++) {
            code.append("int caller").append(i).append("(int x) { return square(x) + ").append(i).append("; }\n");
        }

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code.toString(), "clang", false);

        OptimizationResult o2 = results.get(2);
        assertThat(o2.isSuccess()).as(o2.getErrorMessage()).isTrue();
        assertThat(o2.getRemarks()).hasSize(CompilerService.MAX_REMARKS_PER_LEVEL);
        assertThat(o2.getOmittedRemarkCount()).isGreaterThanOrEqualTo(50);
        // 소스 줄 순서라서 앞쪽 줄의 remark가 남는다
        assertThat(o2.getRemarks().get(0).line()).isEqualTo(2);
    }

    @Test
    void 컴파일에_실패한_레벨에는_remark가_없다() {
        List<OptimizationResult> results = compilerService.compareOptimizationLevels(
                "int f(int x) { retrun x + ; }", "clang", false);

        assertThat(results).allSatisfy(r -> assertThat(r.getRemarks()).isNull());
    }

    @Test
    void PATH의_이름이_아니라_절대_경로로_지정한_컴파일러로도_컴파일과_실행이_된다() {
        // OPTVIS_CLANG 환경 변수나 Windows의 기본 설치 위치(C:\msys64\..., C:\Program Files\LLVM\...)처럼
        // 절대 경로로 컴파일러를 찾은 경우의 경로를 검증한다 (경로에 공백이 있어도 동작해야 함).
        Path clangPath = findOnPath("clang");
        assumeTrue(clangPath != null);

        CompilerProperties properties = new CompilerProperties();
        properties.setOptions(List.of(
                option("clang", "Clang (LLVM)", Language.C, ToolchainKind.CLANG, clangPath.toString())));
        ProcessRunner processRunner = new ProcessRunner();
        CompilerRegistry absoluteRegistry = new CompilerRegistry(properties, processRunner);
        CompilerService service = new CompilerService(absoluteRegistry, processRunner);

        assertThat(absoluteRegistry.resolve("clang").toolDir()).isEqualTo(clangPath.getParent());

        String code = """
                int add(int a, int b) { return a + b; }
                int main(void) { return add(1, 2) - 3; }
                """;
        List<OptimizationResult> results = service.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getExecutionTimeMs()).isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"clangxx", "gxx"})
    void C_plus_plus_코드는_O0부터_O3까지_모두_컴파일에_성공한다(String compilerId) {
        RequiredCompilers.assumeAvailable(registry, compilerId);
        String code = """
                template <typename T>
                T square(T x) { return x * x; }

                struct Accumulator {
                    int total = 0;
                    void add(int x) { total += square(x); }
                };

                int sum_loop(int n) {
                    Accumulator acc;
                    for (int i = 0; i < n; i++) { acc.add(i); }
                    return acc.total;
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, compilerId, false);

        assertThat(results).extracting(OptimizationResult::getLevel).containsExactly("O0", "O1", "O2", "O3");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getCompilerId()).isEqualTo(compilerId);
            // C++ 심볼은 맹글링되지만(_Z8sum_loopi) 원래 이름은 그 안에 그대로 들어 있다
            assertThat(r.getAssembly()).contains("sum_loop");
            assertThat(r.getBinarySizeBytes()).isGreaterThan(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"clangxx", "gxx"})
    void C_plus_plus_표준_헤더를_쓰는_코드도_컴파일된다(String compilerId) {
        RequiredCompilers.assumeAvailable(registry, compilerId);
        String code = """
                #include <vector>

                int sum(const std::vector<int>& values) {
                    int total = 0;
                    for (int v : values) { total += v; }
                    return total;
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, compilerId, false);

        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"clangxx", "gxx"})
    void C_plus_plus_코드도_main이_있으면_실행_시간이_측정된다(String compilerId) {
        // C++ 실행 파일은 C++ 표준 라이브러리까지 링크되어야 하고, MinGW의 g++로 만든 실행 파일은
        // 실행할 때 그 라이브러리의 DLL도 찾을 수 있어야 한다.
        RequiredCompilers.assumeAvailable(registry, compilerId);
        String code = """
                template <typename T>
                T add(T a, T b) { return a + b; }
                int main() { return add(1, 2) - 3; }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, compilerId, true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull();
        });
    }

    @Test
    void C_plus_plus_문법_오류는_input_cpp_파일_이름으로_표시된다() {
        RequiredCompilers.assumeAvailable(registry, "clangxx");
        String brokenCode = "template <typename T> T f(T x) { retrun x; }\nint g() { return f(1); }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(brokenCode, "clangxx", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getErrorMessage()).contains("input.cpp");
            assertThat(r.getErrorMessage()).doesNotContain("opt-visualizer-");
        });
    }

    @Test
    void Rust_라이브러리_코드는_모든_레벨에서_컴파일에_성공한다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = """
                fn square(x: i32) -> i32 {
                    x * x
                }

                pub fn sum_loop(n: i32) -> i32 {
                    (0..n).map(square).sum()
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", false);

        assertThat(results).extracting(OptimizationResult::getLevel).containsExactly("0", "1", "2", "3", "s", "z");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getCompilerId()).isEqualTo("rustc");
            // Rust 심볼은 맹글링되지만 원래 이름은 그 안에 그대로 들어 있다
            assertThat(r.getAssembly()).contains("sum_loop");
            assertThat(r.getAssembly()).doesNotContain("\r");
            assertThat(r.getBinarySizeBytes()).isGreaterThan(0);
            assertThat(r.getNotice()).isNull();
            // rustc는 remark를 내지 않는 계열이라 빈 목록이 아니라 null이다 (화면이 remark 영역을 그리지 않는다)
            assertThat(r.getRemarks()).isNull();
        });
    }

    @Test
    void 작은_pub_함수도_최적화_레벨에서_어셈블리에_남는다() {
        // rustc는 작은 pub fn을 쓰는 쪽 크레이트에서 인라인하려고 미뤄서 O1부터 어셈블리에서 지운다.
        // -C incremental로 막고 있는데 공식 동작이 아니므로, rustc가 바뀌면 이 테스트가 알려준다.
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = "pub fn add(a: i32, b: i32) -> i32 { a + b }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getAssembly()).contains("add");
            assertThat(r.getNotice()).isNull();
        });
    }

    @Test
    void 비공개_함수만_있는_Rust_코드는_성공하지만_안내_문구가_붙는다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = "fn square(x: i32) -> i32 { x * x }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getAssembly()).doesNotContain("square");
            assertThat(r.getNotice()).contains("pub fn");
        });
    }

    @Test
    void main이_있는_Rust_코드는_프로그램으로_컴파일되어_실행_시간이_측정된다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = """
                fn add(a: i32, b: i32) -> i32 {
                    a + b
                }

                fn main() {
                    println!("{}", add(1, 2));
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getAssembly()).contains("main");
            assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull();
        });
    }

    @Test
    void Rust_코드가_패닉으로_끝난_레벨은_실행_시간을_재지_않는다() {
        // 오버플로 검사는 레벨 0에서만 켜진다. 패닉으로 바로 끝난 시간을 그대로 재면 레벨 0이 가장 빠르게 보인다.
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = """
                use std::hint::black_box;

                fn main() {
                    println!("{}", black_box(i32::MAX) + black_box(1));
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", true);

        OptimizationResult level0 = results.get(0);
        assertThat(level0.getExecutionTimeMs()).isNull();
        assertThat(level0.getExecutionError()).contains("101");

        OptimizationResult level3 = results.get(3);
        assertThat(level3.getExecutionTimeMs()).as(level3.getExecutionError()).isNotNull();
    }

    @Test
    void main이_없는_Rust_코드는_실행만_건너뛴다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = "pub fn add(a: i32, b: i32) -> i32 { a + b }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getExecutionTimeMs()).isNull();
            assertThat(r.getExecutionError()).contains("실행 파일을 만들 수 없습니다");
        });
    }

    @Test
    void 시간_초과된_레벨은_다른_레벨이_성공했으면_다시_컴파일한다() {
        RequiredCompilers.assumeAvailable(registry, "clang");
        AtomicInteger compilations = new AtomicInteger();
        ProcessRunner firstCompileTimesOut = new ProcessRunner() {
            @Override
            public ProcessResult run(List<String> command, Path workDir, Path toolDir, Duration timeout)
                    throws IOException, InterruptedException {
                if (compilations.incrementAndGet() == 1) {
                    return new ProcessResult(-1, "", true, 0);
                }
                return super.run(command, workDir, toolDir, timeout);
            }
        };
        CompilerService service = new CompilerService(registry, firstCompileTimesOut);

        List<OptimizationResult> results = service.compareOptimizationLevels("int add(int a, int b) { return a + b; }", "clang", false);

        assertThat(results).extracting(OptimizationResult::getLevel).containsExactly("O0", "O1", "O2", "O3");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getCompilerId()).isEqualTo("clang");
        });
    }

    @Test
    void 모든_레벨이_시간_초과면_다시_컴파일하지_않는다() {
        RequiredCompilers.assumeAvailable(registry, "clang");
        AtomicInteger compilations = new AtomicInteger();
        ProcessRunner alwaysTimesOut = new ProcessRunner() {
            @Override
            public ProcessResult run(List<String> command, Path workDir, Path toolDir, Duration timeout) {
                compilations.incrementAndGet();
                return new ProcessResult(-1, "", true, 0);
            }
        };
        CompilerService service = new CompilerService(registry, alwaysTimesOut);

        List<OptimizationResult> results = service.compareOptimizationLevels("int add(void);", "clang", false);

        assertThat(results).allSatisfy(r -> assertThat(r.getErrorMessage()).contains("시간 초과"));
        assertThat(compilations).hasValue(4);
    }

    @Test
    void 처음_쓰는_컴파일러는_첫_레벨을_혼자_컴파일한_뒤_나머지_레벨을_동시에_컴파일한다() {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        OverlapRecordingRunner runner = new OverlapRecordingRunner();
        CompilerService service = new CompilerService(registry, runner);

        List<OptimizationResult> results = service.compareOptimizationLevels(INLINING_CODE, "clang", false);

        assertThat(results).extracting(OptimizationResult::getLevel).containsExactly("O0", "O1", "O2", "O3");
        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue());
        // 첫 레벨은 디스크의 파일을 OS 캐시에 올려두는 역할이라 다른 레벨과 겹치지 않는다
        assertThat(runner.overlappedLevelDirs).doesNotContain("level-O0").contains("level-O1", "level-O2");
    }

    @Test
    void 한_번_컴파일에_성공한_컴파일러는_첫_레벨부터_동시에_컴파일한다() {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        OverlapRecordingRunner runner = new OverlapRecordingRunner();
        CompilerService service = new CompilerService(registry, runner);
        service.compareOptimizationLevels(INLINING_CODE, "clang", false);
        runner.overlappedLevelDirs.clear();

        List<OptimizationResult> results = service.compareOptimizationLevels(INLINING_CODE, "clang", false);

        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue());
        assertThat(runner.overlappedLevelDirs).contains("level-O0");
    }

    @Test
    void 실행_시간은_컴파일이_모두_끝난_뒤에_한_레벨씩_잰다() {
        OverlapRecordingRunner runner = new OverlapRecordingRunner();
        CompilerService service = new CompilerService(registry, runner);
        String code = """
                int add(int a, int b) { return a + b; }
                int main(void) { return add(1, 2); }
                """;

        List<OptimizationResult> results = service.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull());
        // 시간을 재는 동안에는 컴파일러도, 다른 레벨의 프로그램도 돌지 않는다
        assertThat(runner.maxProcessesDuringMeasurement).hasValue(1);
    }

    @Test
    void 새_실행_파일의_첫_실행은_시간을_재기_전에_레벨끼리_동시에_한다() {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        String code = """
                int add(int a, int b) { return a + b; }
                int main(void) { return add(1, 2); }
                """;
        // 첫 실행 둘이 서로를 기다린다. 첫 실행을 한 레벨씩 하면 먼저 시작한 쪽이 혼자 기다리다 시간이 다 된다
        CountDownLatch twoFirstRuns = new CountDownLatch(2);
        AtomicBoolean waitedAlone = new AtomicBoolean();
        Set<String> startedPrograms = ConcurrentHashMap.newKeySet();
        ProcessRunner runner = new ProcessRunner() {
            @Override
            public ProcessResult runDiscardingOutput(List<String> command, Path workDir, Path toolDir, Duration timeout)
                    throws IOException, InterruptedException {
                if (startedPrograms.add(command.get(0))) {
                    twoFirstRuns.countDown();
                    if (!twoFirstRuns.await(10, TimeUnit.SECONDS)) {
                        waitedAlone.set(true);
                    }
                }
                return super.runDiscardingOutput(command, workDir, toolDir, timeout);
            }
        };
        CompilerService service = new CompilerService(registry, runner);
        // 처음 쓰는 컴파일러는 첫 레벨을 혼자 빌드하므로, 한 번 컴파일해 둬서 모든 레벨을 동시에 빌드하게 한다
        service.compareOptimizationLevels(code, "clang", false);

        List<OptimizationResult> results = service.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull());
        assertThat(waitedAlone).isFalse();
    }

    @Test
    void 첫_실행이_끝나지_않아도_실행_시간은_따로_잰다() {
        String code = """
                int add(int a, int b) { return a + b; }
                int main(void) { return add(1, 2); }
                """;
        // 첫 실행은 다른 레벨의 컴파일과 CPU를 나눠 쓰는 중이라 시간 초과가 측정할 때와 다를 수 있다
        Set<String> startedPrograms = ConcurrentHashMap.newKeySet();
        ProcessRunner firstRunTimesOut = new ProcessRunner() {
            @Override
            public ProcessResult runDiscardingOutput(List<String> command, Path workDir, Path toolDir, Duration timeout)
                    throws IOException, InterruptedException {
                if (startedPrograms.add(command.get(0))) {
                    return new ProcessResult(-1, "", true, timeout.toNanos());
                }
                return super.runDiscardingOutput(command, workDir, toolDir, timeout);
            }
        };
        CompilerService service = new CompilerService(registry, firstRunTimesOut);

        List<OptimizationResult> results = service.compareOptimizationLevels(code, "clang", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull();
            assertThat(r.getExecutionError()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pub struct A;\nimpl A { pub fn main(&self) -> i32 { 1 } }\npub fn keep() -> i32 { A.main() }",
            "pub const S: &str = \"fn main()\";\npub fn keep() -> &'static str { S }",
            "mod m { pub fn main() {} }\npub fn keep() { m::main() }",
    })
    void 진입점이_아닌_fn_main이_있는_Rust_코드는_라이브러리로_컴파일된다(String code) {
        RequiredCompilers.assumeAvailable(registry, "rustc");

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", true);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).as(r.getErrorMessage()).isTrue();
            assertThat(r.getAssembly()).contains("keep");
            // 실행 파일은 진입점이 없어서 만들 수 없다
            assertThat(r.getExecutionError()).contains("E0601");
        });
    }

    @Test
    void 진입점이_아닌_fn_main이_있는_Rust_코드의_에러에는_E0601이_섞이지_않는다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = "pub struct A;\nimpl A { pub fn main(&self) -> i32 { \"x\" } }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getErrorMessage()).contains("E0308").doesNotContain("E0601");
        });
    }

    @Test
    void 표준_입력을_읽는_Rust_프로그램도_실행_시간이_측정된다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String code = """
                fn main() {
                    let mut line = String::new();
                    std::io::stdin().read_line(&mut line).unwrap();
                    println!("{}", line.len());
                }
                """;

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(code, "rustc", true);

        assertThat(results).allSatisfy(
                r -> assertThat(r.getExecutionTimeMs()).as(r.getExecutionError()).isNotNull());
    }

    @Test
    void Rust_컴파일_에러는_input_rs_파일_이름으로_표시된다() {
        RequiredCompilers.assumeAvailable(registry, "rustc");
        String brokenCode = "pub fn f(x: i32) -> i32 { retrun x; }";

        List<OptimizationResult> results = compilerService.compareOptimizationLevels(brokenCode, "rustc", false);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getErrorMessage()).contains("input.rs");
            assertThat(r.getErrorMessage()).doesNotContain("opt-visualizer-");
        });
    }

    /**
     * 실제로 프로세스를 실행하면서, 어느 레벨의 프로세스가 다른 레벨과 겹쳐 돌았는지 기록한다.
     * 레벨은 작업 디렉터리 이름(level-O0 등)으로 구분한다. 컴파일러가 아주 빨리 끝나면 동시에 시작해도 겹치지 않을 수
     * 있어서, 컴파일 명령마다 시작 전에 조금 기다린다.
     */
    private static final class OverlapRecordingRunner extends ProcessRunner {
        private static final long COMPILE_DELAY_MS = 200;

        private final Set<String> runningLevelDirs = ConcurrentHashMap.newKeySet();
        private final AtomicInteger runningProcesses = new AtomicInteger();
        private final Set<String> startedPrograms = ConcurrentHashMap.newKeySet();
        final Set<String> overlappedLevelDirs = ConcurrentHashMap.newKeySet();
        final AtomicInteger maxProcessesDuringMeasurement = new AtomicInteger();

        @Override
        public ProcessResult run(List<String> command, Path workDir, Path toolDir, Duration timeout)
                throws IOException, InterruptedException {
            String levelDir = workDir.getFileName().toString();
            synchronized (this) {
                if (!runningLevelDirs.isEmpty()) {
                    overlappedLevelDirs.add(levelDir);
                    overlappedLevelDirs.addAll(runningLevelDirs);
                }
                runningLevelDirs.add(levelDir);
            }
            runningProcesses.incrementAndGet();
            try {
                Thread.sleep(COMPILE_DELAY_MS);
                return super.run(command, workDir, toolDir, timeout);
            } finally {
                runningProcesses.decrementAndGet();
                runningLevelDirs.remove(levelDir);
            }
        }

        @Override
        public ProcessResult runDiscardingOutput(List<String> command, Path workDir, Path toolDir, Duration timeout)
                throws IOException, InterruptedException {
            // 실행 파일마다 첫 실행은 시간을 재지 않는 준비 실행이라 컴파일하는 동안 다른 레벨과 함께 돈다
            boolean measured = !startedPrograms.add(command.get(0));
            int running = runningProcesses.incrementAndGet();
            if (measured) {
                maxProcessesDuringMeasurement.accumulateAndGet(running, Math::max);
            }
            try {
                return super.runDiscardingOutput(command, workDir, toolDir, timeout);
            } finally {
                runningProcesses.decrementAndGet();
            }
        }
    }

    private static boolean isAppliedInlining(OptRemark remark) {
        return remark.kind() == OptRemark.Kind.APPLIED && "함수 인라이닝".equals(remark.topicLabel());
    }

    private static CompilerProperties.Option option(String id, String label, Language language,
                                                    ToolchainKind toolchain, String... candidates) {
        CompilerProperties.Option option = new CompilerProperties.Option();
        option.setId(id);
        option.setLabel(label);
        option.setLanguage(language);
        option.setToolchain(toolchain);
        option.setCandidates(List.of(candidates));
        return option;
    }

    /** PATH에서 실행 파일의 절대 경로를 찾는다 (Windows는 .exe가 붙은 이름도 확인). 없으면 null. */
    private static Path findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            for (String fileName : List.of(name, name + ".exe")) {
                try {
                    Path candidate = Path.of(dir, fileName);
                    if (Files.isRegularFile(candidate) && candidate.isAbsolute()) {
                        return candidate;
                    }
                } catch (InvalidPathException ignored) {
                }
            }
        }
        return null;
    }
}
