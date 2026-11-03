package com.compareopt.optvisualizer.platform;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 외부 프로세스(컴파일러, 사용자 프로그램)를 실행하는 유일한 창구.
 *
 * 프로세스를 다루는 방식은 OS마다 함정이 달라서 한 곳에 모아둔다:
 * <ul>
 *   <li>출력을 파이프가 아니라 파일로 받는다 — 파이프 버퍼(Windows는 수 KB)가 차면 컴파일러가
 *       출력 대기 상태로 멈춰서, 경고/에러가 많은 코드가 "시간 초과"로 잘못 처리되기 때문.</li>
 *   <li>타임아웃 시 자식 프로세스까지 함께 죽인다 — 드라이버(gcc)만 죽이면 실제 컴파일러(cc1)가
 *       살아남고, Windows에서는 그 프로세스가 임시 파일을 잡고 있어 정리가 실패한다.</li>
 *   <li>표준 입력을 바로 닫는다 — 입력을 읽는 프로그램이 오지 않을 입력을 기다리다 "시간 초과"로 처리되기 때문.</li>
 *   <li>PATH 환경 변수 이름의 대소문자(Windows는 보통 "Path")를 가리지 않고 다룬다.</li>
 * </ul>
 */
@Component
public class ProcessRunner {

    private static final long KILL_WAIT_SECONDS = 2;

    /**
     * stdout+stderr를 합쳐서 캡처하며 실행한다.
     *
     * @param workDir 프로세스의 작업 디렉터리 (null이면 서버의 작업 디렉터리)
     * @param toolDir 자식 프로세스의 PATH 맨 앞에 추가할 디렉터리 (null이면 추가하지 않음)
     * @throws IOException 실행 파일이 없는 등 프로세스를 시작하지 못한 경우
     */
    public ProcessResult run(List<String> command, Path workDir, Path toolDir, Duration timeout)
            throws IOException, InterruptedException {
        Path outputFile = workDir != null
                ? Files.createTempFile(workDir, "process-", ".log")
                : Files.createTempFile("opt-visualizer-process-", ".log");
        try {
            ProcessBuilder pb = newBuilder(command, workDir, toolDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());

            ProcessResult exit = startAndWait(pb, timeout);
            // 컴파일러가 UTF-8이 아닌 바이트를 섞어 출력해도 예외 없이 읽히도록 관대한 디코딩을 쓴다.
            String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
            return new ProcessResult(exit.exitCode(), normalizeNewlines(output), exit.timedOut(), exit.elapsedNanos());
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** 출력을 모두 버리고 실행한다 (사용자 프로그램의 실행 시간만 잴 때 사용). */
    public ProcessResult runDiscardingOutput(List<String> command, Path workDir, Path toolDir, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = newBuilder(command, workDir, toolDir);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return startAndWait(pb, timeout);
    }

    /** Windows 도구가 쓰는 \r\n 줄바꿈을 \n으로 통일한다. */
    public static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n");
    }

    private static ProcessBuilder newBuilder(List<String> command, Path workDir, Path toolDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        if (toolDir != null) {
            prependToPath(pb.environment(), toolDir.toString(), HostOs.current() == HostOs.WINDOWS);
        }
        return pb;
    }

    /**
     * 환경 변수 맵의 PATH 맨 앞에 디렉터리를 추가한다.
     *
     * Windows의 환경 변수 이름은 대소문자를 구분하지 않고 보통 "Path"로 들어 있는데,
     * ProcessBuilder.environment()가 돌려주는 맵은 대소문자를 구분한다. 그대로 "PATH"로 put하면
     * "Path"와 "PATH"가 둘 다 생기므로, 이미 있는 키를 찾아서 그 키를 갱신해야 한다.
     */
    static void prependToPath(Map<String, String> env, String dir, boolean caseInsensitiveNames) {
        String key = "PATH";
        if (caseInsensitiveNames) {
            key = env.keySet().stream()
                    .filter(name -> name.equalsIgnoreCase("PATH"))
                    .findFirst()
                    .orElse("PATH");
        }
        String current = env.get(key);
        env.put(key, current == null || current.isEmpty() ? dir : dir + File.pathSeparator + current);
    }

    private static ProcessResult startAndWait(ProcessBuilder pb, Duration timeout)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        Process process = pb.start();
        try {
            process.getOutputStream().close();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.nanoTime() - t0;
            if (!finished) {
                killTree(process);
                return new ProcessResult(-1, "", true, elapsed);
            }
            return new ProcessResult(process.exitValue(), "", false, elapsed);
        } catch (IOException | InterruptedException e) {
            killTree(process);
            throw e;
        }
    }

    /** 자식 프로세스들을 먼저 죽이고 본체를 죽인 뒤, 파일 핸들이 풀리도록 잠깐 종료를 기다린다. */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
