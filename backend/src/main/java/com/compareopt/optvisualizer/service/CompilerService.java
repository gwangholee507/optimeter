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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
            Files.writeString(workDir.resolve(compiler.language().sourceFileName()), sourceCode, StandardCharsets.UTF_8);

            for (OptLevel level : levels) {
                OptimizationResult result = runOneLevel(workDir, level, compiler, toolchain, runBenchmark);
                Optional<Toolchain> corrected = result.isSuccess()
                        ? Optional.empty() : toolchain.correctedBy(result.getErrorMessage());
                if (corrected.isPresent()) {
                    // 남은 레벨도 바로잡은 명령줄로 컴파일한다
                    toolchain = corrected.get();
                    result = runOneLevel(workDir, level, compiler, toolchain, runBenchmark);
                }
                result.setCompilerInfo(compiler.id(), compiler.label());
                results.add(result);
            }
            retryTimedOutLevels(results, levels, workDir, compiler, toolchain, runBenchmark);
        } catch (IOException e) {
            log.error("임시 작업 디렉터리 생성 실패", e);
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
     * 첫 컴파일이 디스크를 읽느라 제한 시간을 넘겨도, 읽은 내용은 OS 캐시에 남아서 다시 하면 금방 끝난다.
     * 그래서 다른 레벨이 컴파일됐다면 코드 문제가 아닌 것으로 보고 시간 초과된 레벨을 한 번 더 컴파일한다.
     * 모든 레벨이 시간 초과면 컴파일이 정말 오래 걸리는 코드이므로 다시 하지 않는다.
     */
    private void retryTimedOutLevels(List<OptimizationResult> results, List<OptLevel> levels, Path workDir,
                                     CompilerRegistry.ResolvedCompiler compiler, Toolchain toolchain,
                                     boolean runBenchmark) {
        if (results.stream().noneMatch(OptimizationResult::isSuccess)) {
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            if (COMPILE_TIMEOUT_MESSAGE.equals(results.get(i).getErrorMessage())) {
                OptimizationResult retried = runOneLevel(workDir, levels.get(i), compiler, toolchain, runBenchmark);
                retried.setCompilerInfo(compiler.id(), compiler.label());
                results.set(i, retried);
            }
        }
    }

    private OptimizationResult runOneLevel(Path workDir, OptLevel level, CompilerRegistry.ResolvedCompiler compiler,
                                            Toolchain toolchain, boolean runBenchmark) {
        long start = System.currentTimeMillis();
        String sourceName = compiler.language().sourceFileName();
        String asmName = "out-" + level.id() + ".s";
        String objName = "out-" + level.id() + ".o";
        List<List<String>> commands = toolchain.codegenCommands(level, sourceName, asmName, objName);

        // 1) 어셈블리 생성 (예: {compiler} -S -O{n} input.c -o out.s). 최적화 remark도 이 명령이 함께 출력한다.
        ProcessResult asmResult = runCompiler(workDir, compiler, commands.get(0));
        long elapsed = System.currentTimeMillis() - start;

        if (!asmResult.succeeded()) {
            return OptimizationResult.failure(level, asmResult.output(), elapsed);
        }

        // 2) 오브젝트 파일 생성 (크기 비교용, 예: {compiler} -c -O{n} input.c -o out.o)
        //    main() 유무와 무관하게 만들 수 있어야 하므로 링크하지 않는다.
        //    rustc처럼 어셈블리와 한 번에 만드는 컴파일러는 남은 명령이 없다.
        ProcessResult objResult = asmResult;
        for (List<String> args : commands.subList(1, commands.size())) {
            objResult = runCompiler(workDir, compiler, args);
        }

        long binarySize = 0;
        Path objFile = workDir.resolve(objName);
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
                    Files.readString(workDir.resolve(asmName), StandardCharsets.UTF_8));
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

        if (runBenchmark) {
            ExecutionOutcome outcome = benchmarkExecution(workDir, level, compiler, toolchain);
            result.setExecutionResult(outcome.timeMs, outcome.error);
        }

        return result;
    }

    /**
     * 소스를 실행 파일로 링크해서 여러 번 실행하고 최솟값(가장 노이즈가 적은 값)을 실행 시간으로 채택한다.
     * main()이 없는 코드는 링크에 실패하므로 이 경우 에러 메시지만 채우고 조용히 넘어간다
     * (컴파일 자체는 성공했으므로 전체 결과를 실패로 처리하지 않음).
     */
    private ExecutionOutcome benchmarkExecution(Path workDir, OptLevel level,
                                                 CompilerRegistry.ResolvedCompiler compiler, Toolchain toolchain) {
        // 확장자 .exe는 Windows에서 실행 파일로 인식되려면 필요하고, macOS/Linux에서는 있어도 무해하다.
        String exeName = "out-" + level.id() + ".exe";
        Path exeFile = workDir.resolve(exeName);

        String sourceName = compiler.language().sourceFileName();
        ProcessResult linkResult = runCompiler(
                workDir, compiler, toolchain.executableArgs(level, sourceName, exeName));

        if (!linkResult.succeeded() || !Files.exists(exeFile)) {
            return new ExecutionOutcome(null, linkFailureMessage(linkResult.output()));
        }

        try {
            long bestNanos = Long.MAX_VALUE;
            for (int i = 0; i < BENCHMARK_RUNS; i++) {
                ProcessResult run = processRunner.runDiscardingOutput(
                        List.of(exeFile.toString()), workDir, compiler.toolDir(),
                        Duration.ofSeconds(EXECUTION_TIMEOUT_SECONDS));
                if (run.timedOut()) {
                    return new ExecutionOutcome(null, "실행 시간 초과 (" + EXECUTION_TIMEOUT_SECONDS + "초)");
                }
                // 중간에 죽은 프로그램의 시간은 끝까지 돈 다른 레벨과 비교할 수 없다 (실제보다 훨씬 빠르게 보인다)
                Optional<String> failure = toolchain.executionFailure(run.exitCode());
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

    private record ExecutionOutcome(Double timeMs, String error) {
    }
}
