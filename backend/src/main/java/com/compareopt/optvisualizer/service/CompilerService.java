package com.compareopt.optvisualizer.service;

import com.compareopt.optvisualizer.dto.OptimizationResult;
import com.compareopt.optvisualizer.platform.ProcessResult;
import com.compareopt.optvisualizer.platform.ProcessRunner;
import com.compareopt.optvisualizer.toolchain.OptLevel;
import com.compareopt.optvisualizer.toolchain.Toolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 선택된 컴파일러를 서브프로세스로 호출해 최적화 레벨별로
 * 어셈블리와 바이너리 크기, (옵트인 시) 실행 시간을 뽑아내는 서비스.
 *
 * 이 클래스에는 OS별 분기도, 언어·컴파일러별 분기도 두지 않는다.
 * - 프로세스를 띄우고 죽이는 방식, 컴파일러 위치 탐색처럼 OS마다 달라지는 부분은
 *   platform 패키지(ProcessRunner, HostOs)와 CompilerRegistry가 맡는다.
 * - 소스 파일 이름, 최적화 레벨 목록, 컴파일러에 넘길 인자처럼 언어·컴파일러마다 달라지는 부분은
 *   toolchain 패키지(Language, Toolchain)가 맡는다.
 *
 * 레벨끼리는 서로 기다릴 이유가 없어서 동시에 컴파일한다 (buildLevels 참고).
 * 실행 시간만은 컴파일이 모두 끝난 뒤에 한 레벨씩 잰다. 다른 프로세스와 CPU를 나눠 쓰면 측정값이 흔들린다.
 *
 * 주의: 지금은 로컬 개인 프로젝트 단계라 별도 샌드박싱(Docker 등) 없이
 * 서버 프로세스 권한으로 직접 컴파일/실행을 수행합니다.
 * 특히 runBenchmark=true인 경우 사용자가 입력한 코드로 만들어진 바이너리를
 * 그대로 실행하므로 컴파일만 할 때보다 위험도가 높습니다.
 * 외부에 배포할 계획이 생기면 반드시 컨테이너 격리 + 리소스 제한을 추가해야 합니다.
 */
@Service
public class CompilerService {

    private static final Logger log = LoggerFactory.getLogger(CompilerService.class);
    // 새로 켠 컴퓨터의 첫 컴파일은 컴파일러와 표준 라이브러리를 디스크에서 읽느라 짧은 코드도 10초를 넘길 수 있다.
    private static final long COMPILE_TIMEOUT_SECONDS = 30;
    private static final String COMPILE_TIMEOUT_MESSAGE = "컴파일 시간 초과 (" + COMPILE_TIMEOUT_SECONDS + "초)";
    private static final long EXECUTION_TIMEOUT_SECONDS = 5;
    private static final int BENCHMARK_RUNS = 5;
    private static final int LINK_ERROR_DETAIL_MAX_CHARS = 1000;
    // 함수가 수백 개인 코드는 인라이닝 remark만으로 수천 개가 된다. 화면에서 읽을 수 있는 양만 보낸다.
    static final int MAX_REMARKS_PER_LEVEL = 200;
    private static final int CLEANUP_ATTEMPTS = 3;
    private static final long CLEANUP_RETRY_DELAY_MS = 100;

    private final CompilerRegistry compilerRegistry;
    private final ProcessRunner processRunner;
    // 이 서버 프로세스에서 컴파일에 성공한 적이 있는 컴파일러의 id.
    // 그 컴파일러와 표준 라이브러리는 이미 OS의 파일 캐시에 올라와 있다고 보고, 첫 레벨부터 동시에 컴파일한다.
    private final Set<String> warmedUpCompilers = ConcurrentHashMap.newKeySet();

    public CompilerService(CompilerRegistry compilerRegistry, ProcessRunner processRunner) {
        this.compilerRegistry = compilerRegistry;
        this.processRunner = processRunner;
    }

    public List<OptimizationResult> compareOptimizationLevels(String sourceCode, String compilerId, boolean runBenchmark) {
        CompilerRegistry.ResolvedCompiler compiler = compilerRegistry.resolve(compilerId);
        Toolchain toolchain = compiler.toolchain().forSource(sourceCode);
        List<OptLevel> levels = toolchain.levels();

        List<OptimizationResult> results = new ArrayList<>();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("opt-visualizer-");
            for (LevelBuild build : buildLevels(workDir, sourceCode, levels, compiler, toolchain, runBenchmark)) {
                OptimizationResult result = build.result();
                if (build.executable() != null) {
                    ExecutionOutcome outcome = benchmarkExecution(build, compiler);
                    result.setExecutionResult(outcome.timeMs, outcome.error);
                } else if (build.executableError() != null) {
                    result.setExecutionResult(null, build.executableError());
                }
                result.setCompilerInfo(compiler.id(), compiler.label());
                results.add(result);
            }
        } catch (IOException e) {
            log.error("임시 작업 디렉터리 생성 실패", e);
            results.clear();
            for (OptLevel level : levels) {
                OptimizationResult failure = OptimizationResult.failure(level, "서버 내부 오류: " + e.getMessage(), 0);
                failure.setCompilerInfo(compiler.id(), compiler.label());
                results.add(failure);
            }
        } finally {
            cleanup(workDir);
        }
        return results;
    }

    /**
     * 모든 레벨을 컴파일하고, 실행 시간도 잴 때는 실행 파일까지 만든다. 결과는 levels 순서다.
     *
     * 레벨마다 작업 디렉터리를 따로 만들어 소스를 넣는다. 레벨끼리 동시에 컴파일하는데, rustc는 레벨과 상관없이
     * 같은 이름의 중간 파일(input.input.{해시}-cgu.0.rcgu.o)을 작업 디렉터리에 써서 한 곳에서 하면 서로 덮어쓴다.
     *
     * 이 서버에서 처음 쓰는 컴파일러는 첫 레벨만 먼저 컴파일한다. 새로 켠 컴퓨터에서는 컴파일러와 표준 라이브러리를
     * 디스크에서 읽는 첫 컴파일이 수십 초 걸릴 수 있는데(CI의 새 VM에서 rustc 약 78초), 모든 레벨을 한꺼번에 시작하면
     * 다 같이 시간 초과가 되어 retryTimedOutLevels가 다시 해 볼 근거(성공한 레벨)가 남지 않는다.
     * 첫 레벨이 읽어 둔 파일은 OS 캐시에 남아서 나머지 레벨은 금방 끝난다. 첫 레벨에서 바로잡은 명령줄(correctedBy)도
     * 나머지 레벨에 그대로 쓴다.
     */
    private List<LevelBuild> buildLevels(Path workDir, String sourceCode, List<OptLevel> levels,
                                         CompilerRegistry.ResolvedCompiler compiler, Toolchain toolchain,
                                         boolean withExecutable) throws IOException {
        List<Path> dirs = new ArrayList<>();
        for (OptLevel level : levels) {
            Path dir = Files.createDirectory(workDir.resolve("level-" + level.id()));
            Files.writeString(dir.resolve(compiler.language().sourceFileName()), sourceCode, StandardCharsets.UTF_8);
            dirs.add(dir);
        }

        LevelBuild[] builds = new LevelBuild[levels.size()];
        int next = 0;
        if (!warmedUpCompilers.contains(compiler.id())) {
            builds[0] = buildLevel(dirs.get(0), levels.get(0), compiler, toolchain, withExecutable);
            toolchain = builds[0].toolchain();
            next = 1;
        }
        if (next == 1 && builds[0].timedOut()) {
            // 첫 레벨도 시간 초과면 디스크를 읽는 중이거나 컴파일이 정말 오래 걸리는 코드다.
            // 동시에 하면 또 다 같이 시간 초과되므로 한 레벨씩 한다.
            for (int i = 1; i < builds.length; i++) {
                builds[i] = buildLevel(dirs.get(i), levels.get(i), compiler, toolchain, withExecutable);
            }
        } else {
            buildInParallel(builds, next, dirs, levels, compiler, toolchain, withExecutable);
        }

        if (Arrays.stream(builds).anyMatch(build -> build.result().isSuccess())) {
            warmedUpCompilers.add(compiler.id());
            retryTimedOutLevels(builds, compiler, withExecutable);
        }
        return List.of(builds);
    }

    /** builds[from..]을 동시에 채운다. 동시에 띄우는 컴파일러 수는 CPU 코어 수를 넘지 않는다. */
    private void buildInParallel(LevelBuild[] builds, int from, List<Path> dirs, List<OptLevel> levels,
                                 CompilerRegistry.ResolvedCompiler compiler, Toolchain toolchain,
                                 boolean withExecutable) {
        int count = builds.length - from;
        if (count == 0) {
            return;
        }
        ExecutorService pool = newCompilePool(Math.min(count, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<LevelBuild>> futures = new ArrayList<>();
            for (int i = from; i < builds.length; i++) {
                Path dir = dirs.get(i);
                OptLevel level = levels.get(i);
                futures.add(pool.submit(() -> buildLevel(dir, level, compiler, toolchain, withExecutable)));
            }
            for (int i = from; i < builds.length; i++) {
                builds[i] = await(futures.get(i - from), dirs.get(i), levels.get(i), toolchain);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static ExecutorService newCompilePool(int threads) {
        AtomicInteger created = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "compile-level-" + created.incrementAndGet());
            // 요청이 끝나면 shutdownNow로 정리하지만, 혹시 남더라도 서버 종료를 막지 않게 한다
            thread.setDaemon(true);
            return thread;
        });
    }

    private LevelBuild await(Future<LevelBuild> future, Path dir, OptLevel level, Toolchain toolchain) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            log.error("{} 레벨 컴파일 중 예외", level.id(), e.getCause());
            return LevelBuild.failed(dir, level, toolchain, "서버 내부 오류: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LevelBuild.failed(dir, level, toolchain, "컴파일이 중단되었습니다.");
        }
    }

    /**
     * 첫 컴파일이 디스크를 읽느라 제한 시간을 넘겨도, 읽은 내용은 OS 캐시에 남아서 다시 하면 금방 끝난다.
     * 동시에 컴파일한 레벨이 CPU를 나눠 쓰느라 제한 시간을 넘겼을 수도 있다.
     * 그래서 다른 레벨이 컴파일됐다면 코드 문제가 아닌 것으로 보고 시간 초과된 레벨을 한 레벨씩 다시 컴파일한다.
     * 모든 레벨이 시간 초과면 컴파일이 정말 오래 걸리는 코드이므로 다시 하지 않는다 (호출하는 쪽에서 거른다).
     */
    private void retryTimedOutLevels(LevelBuild[] builds, CompilerRegistry.ResolvedCompiler compiler,
                                     boolean withExecutable) {
        for (int i = 0; i < builds.length; i++) {
            LevelBuild build = builds[i];
            if (build.timedOut()) {
                builds[i] = buildLevel(build.dir(), build.level(), compiler, build.toolchain(), withExecutable);
            }
        }
    }

    /**
     * 한 레벨을 컴파일하고, withExecutable이면 실행 파일도 만든다.
     * 컴파일러의 에러로 명령줄이 틀렸음을 알 수 있으면(correctedBy) 바로잡은 명령줄로 한 번 더 컴파일한다.
     */
    private LevelBuild buildLevel(Path dir, OptLevel level, CompilerRegistry.ResolvedCompiler compiler,
                                  Toolchain toolchain, boolean withExecutable) {
        OptimizationResult result = compileLevel(dir, level, compiler, toolchain);
        Optional<Toolchain> corrected = result.isSuccess()
                ? Optional.empty() : toolchain.correctedBy(result.getErrorMessage());
        if (corrected.isPresent()) {
            toolchain = corrected.get();
            result = compileLevel(dir, level, compiler, toolchain);
        }

        LevelBuild build = new LevelBuild(dir, level, toolchain, result, null, null);
        if (withExecutable && result.isSuccess()) {
            return linkExecutable(build, compiler);
        }
        return build;
    }

    private OptimizationResult compileLevel(Path dir, OptLevel level, CompilerRegistry.ResolvedCompiler compiler,
                                            Toolchain toolchain) {
        long start = System.currentTimeMillis();
        String sourceName = compiler.language().sourceFileName();
        String asmName = asmFileName(level);
        String objName = objFileName(level);
        List<List<String>> commands = toolchain.codegenCommands(level, sourceName, asmName, objName);

        // 1) 어셈블리 생성 (예: {compiler} -S -O{n} input.c -o out.s). 최적화 remark도 이 명령이 함께 출력한다.
        ProcessResult asmResult = runCompiler(dir, compiler, commands.get(0));
        long elapsed = System.currentTimeMillis() - start;

        if (!asmResult.succeeded()) {
            return OptimizationResult.failure(level, asmResult.output(), elapsed);
        }

        // 2) 오브젝트 파일 생성 (크기 비교용, 예: {compiler} -c out.s -o out.o)
        //    main() 유무와 무관하게 만들 수 있어야 하므로 링크하지 않는다.
        //    rustc처럼 어셈블리와 한 번에 만드는 컴파일러는 남은 명령이 없다.
        ProcessResult objResult = asmResult;
        for (List<String> args : commands.subList(1, commands.size())) {
            objResult = runCompiler(dir, compiler, args);
        }
        if (!objResult.succeeded()) {
            // 어셈블리는 있으므로 결과는 보여주고 크기만 0으로 둔다. 어셈블러가 컴파일러의 어셈블리를 못 읽은 경우라 로그를 남긴다.
            log.warn("{} 레벨 오브젝트 파일 생성 실패: {}", level.id(), objResult.output());
        }

        long binarySize = 0;
        Path objFile = dir.resolve(objName);
        try {
            if (objResult.succeeded() && Files.exists(objFile)) {
                binarySize = Files.size(objFile);
            }
        } catch (IOException e) {
            log.warn("오브젝트 파일 크기 조회 실패: {}", e.getMessage());
        }

        String assembly;
        try {
            // Windows의 컴파일러는 어셈블리를 \r\n 줄바꿈으로 쓰므로, 어느 OS에서든 같은 형태가 되도록 통일한다.
            assembly = ProcessRunner.normalizeNewlines(
                    Files.readString(dir.resolve(asmName), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return OptimizationResult.failure(level, "어셈블리 파일 읽기 실패: " + e.getMessage(), elapsed);
        }

        long totalElapsed = System.currentTimeMillis() - start;
        OptimizationResult result = OptimizationResult.success(level, assembly, binarySize, totalElapsed);
        toolchain.assemblyNotice(assembly).ifPresent(result::setNotice);
        // remark는 어셈블리를 만든 바로 그 명령의 출력에서 읽는다
        toolchain.remarks(asmResult.output(), sourceName).ifPresent(remarks -> {
            int shown = Math.min(remarks.size(), MAX_REMARKS_PER_LEVEL);
            result.setRemarks(List.copyOf(remarks.subList(0, shown)), remarks.size() - shown);
        });
        return result;
    }

    /**
     * 실행 시간 측정용 실행 파일을 만든다.
     * main()이 없는 코드는 링크에 실패하므로 이 경우 사유만 남기고 조용히 넘어간다
     * (컴파일 자체는 성공했으므로 전체 결과를 실패로 처리하지 않음).
     */
    private LevelBuild linkExecutable(LevelBuild build, CompilerRegistry.ResolvedCompiler compiler) {
        // 확장자 .exe는 Windows에서 실행 파일로 인식되려면 필요하고, macOS/Linux에서는 있어도 무해하다.
        String exeName = "out-" + build.level().id() + ".exe";
        Path exeFile = build.dir().resolve(exeName);

        List<String> args = build.toolchain().executableArgs(
                build.level(), compiler.language().sourceFileName(), objFileName(build.level()), exeName);
        ProcessResult linkResult = runCompiler(build.dir(), compiler, args);

        if (!linkResult.succeeded() || !Files.exists(exeFile)) {
            return build.withExecutableError(linkFailureMessage(linkResult.output()));
        }
        return build.withExecutable(exeFile);
    }

    /** 실행 파일을 여러 번 실행하고 최솟값(가장 노이즈가 적은 값)을 실행 시간으로 채택한다. */
    private ExecutionOutcome benchmarkExecution(LevelBuild build, CompilerRegistry.ResolvedCompiler compiler) {
        try {
            long bestNanos = Long.MAX_VALUE;
            for (int i = 0; i < BENCHMARK_RUNS; i++) {
                ProcessResult run = processRunner.runDiscardingOutput(
                        List.of(build.executable().toString()), build.dir(), compiler.toolDir(),
                        Duration.ofSeconds(EXECUTION_TIMEOUT_SECONDS));
                if (run.timedOut()) {
                    return new ExecutionOutcome(null, "실행 시간 초과 (" + EXECUTION_TIMEOUT_SECONDS + "초)");
                }
                // 중간에 죽은 프로그램의 시간은 끝까지 돈 다른 레벨과 비교할 수 없다 (실제보다 훨씬 빠르게 보인다)
                Optional<String> failure = build.toolchain().executionFailure(run.exitCode());
                if (failure.isPresent()) {
                    return new ExecutionOutcome(null, failure.get());
                }
                bestNanos = Math.min(bestNanos, run.elapsedNanos());
            }
            return new ExecutionOutcome(bestNanos / 1_000_000.0, null);
        } catch (IOException e) {
            return new ExecutionOutcome(null, "실행 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionOutcome(null, "실행이 중단되었습니다.");
        }
    }

    /**
     * 링크 실패 사유. 대부분은 main()이 없어서지만, 링커가 없는 환경(예: Windows에 LLVM만 설치하고
     * Visual Studio Build Tools나 MinGW가 없는 경우)도 있어서 링커의 원본 메시지를 함께 붙여준다.
     */
    private String linkFailureMessage(String linkerOutput) {
        String message = "실행 파일을 만들 수 없습니다 (main 함수가 없거나, 링커가 설치되지 않았을 수 있습니다)";
        String detail = linkerOutput == null ? "" : linkerOutput.strip();
        if (detail.isEmpty()) {
            return message;
        }
        if (detail.length() > LINK_ERROR_DETAIL_MAX_CHARS) {
            detail = detail.substring(0, LINK_ERROR_DETAIL_MAX_CHARS) + "\n...";
        }
        return message + "\n\n" + detail;
    }

    private static String asmFileName(OptLevel level) {
        return "out-" + level.id() + ".s";
    }

    private static String objFileName(OptLevel level) {
        return "out-" + level.id() + ".o";
    }

    /**
     * 컴파일러를 임시 작업 디렉터리 안에서 실행한다.
     *
     * 작업 디렉터리를 workDir로 두고 파일은 상대 경로(input.c, out-O0.s)로만 넘긴다. 그래서
     * 에러 메시지에 서버의 임시 디렉터리 절대 경로 대신 "input.c:1:16: error: ..."처럼 파일 이름만 찍히고,
     * 임시 디렉터리 경로에 공백이나 한글(Windows 사용자 이름 등)이 있어도 인자로 전달되지 않아 안전하다.
     *
     * 프로세스를 시작하지 못했거나 시간 초과면 그 사유를 output에 담은 실패 결과를 돌려준다.
     */
    private ProcessResult runCompiler(Path workDir, CompilerRegistry.ResolvedCompiler compiler, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(compiler.path());
        command.addAll(args);
        try {
            ProcessResult result = processRunner.run(
                    command, workDir, compiler.toolDir(), Duration.ofSeconds(COMPILE_TIMEOUT_SECONDS));
            if (result.timedOut()) {
                return new ProcessResult(-1, COMPILE_TIMEOUT_MESSAGE, true, result.elapsedNanos());
            }
            return result;
        } catch (IOException e) {
            return new ProcessResult(-1, "프로세스 실행 실패: " + e.getMessage(), false, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "컴파일이 중단되었습니다.", false, 0);
        }
    }

    /**
     * 임시 작업 디렉터리를 통째로 지운다.
     * Windows에서는 방금 종료된 실행 파일이 백신 검사 등으로 잠깐 잠겨 있어 삭제가 실패할 수 있어서,
     * 남은 파일이 있으면 짧게 기다렸다가 몇 번 더 시도한다.
     */
    private void cleanup(Path workDir) {
        if (workDir == null) return;
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
            if (deleteRecursively(workDir)) {
                return;
            }
            try {
                Thread.sleep(CLEANUP_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("임시 디렉터리를 완전히 지우지 못했습니다: {}", workDir);
    }

    /** 디렉터리 안의 모든 것을 지우고, 남은 것이 없으면 true를 반환. */
    private boolean deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()) // 파일 먼저, 디렉터리 나중에 삭제
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("임시 디렉터리 정리 실패: {}", e.getMessage());
        }
        return !Files.exists(dir);
    }

    /**
     * 한 레벨을 컴파일한 결과.
     *
     * @param dir             이 레벨만 쓰는 작업 디렉터리
     * @param toolchain       이 레벨을 실제로 컴파일한 명령줄 (correctedBy로 바로잡았으면 바로잡은 것)
     * @param executable      실행 시간을 잴 실행 파일. 재지 않거나 만들지 못했으면 null
     * @param executableError 실행 파일을 만들지 못한 사유. 만들었거나 만들 필요가 없었으면 null
     */
    private record LevelBuild(Path dir, OptLevel level, Toolchain toolchain, OptimizationResult result,
                              Path executable, String executableError) {

        static LevelBuild failed(Path dir, OptLevel level, Toolchain toolchain, String message) {
            return new LevelBuild(dir, level, toolchain, OptimizationResult.failure(level, message, 0), null, null);
        }

        boolean timedOut() {
            return COMPILE_TIMEOUT_MESSAGE.equals(result.getErrorMessage());
        }

        LevelBuild withExecutable(Path exe) {
            return new LevelBuild(dir, level, toolchain, result, exe, null);
        }

        LevelBuild withExecutableError(String error) {
            return new LevelBuild(dir, level, toolchain, result, null, error);
        }
    }

    private record ExecutionOutcome(Double timeMs, String error) {
    }
}
