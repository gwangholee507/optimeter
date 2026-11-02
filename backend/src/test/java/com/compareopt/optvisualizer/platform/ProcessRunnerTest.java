package com.compareopt.optvisualizer.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 어느 OS에나 반드시 있는 실행 파일인 "지금 테스트를 돌리고 있는 java"를 자식 프로세스로 써서 검증한다.
 */
class ProcessRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // Windows에서는 확장자 없이 넘겨도 OS가 java.exe를 찾아준다.
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void 프로세스의_출력을_캡처하고_종료_코드를_돌려준다() throws Exception {
        ProcessResult result = runner.run(List.of(JAVA, "-version"), null, null, TIMEOUT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.timedOut()).isFalse();
        // `java -version`은 stderr로 출력한다 — stdout/stderr가 합쳐져 캡처되는지까지 확인.
        assertThat(result.output()).contains("version");
        assertThat(result.output()).doesNotContain("\r");
        assertThat(result.elapsedNanos()).isGreaterThan(0);
    }

    @Test
    void 실패한_프로세스는_0이_아닌_종료_코드와_출력을_돌려준다() throws Exception {
        ProcessResult result = runner.run(List.of(JAVA, "--no-such-option"), null, null, TIMEOUT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void 작업_디렉터리를_지정하면_출력_임시_파일을_그_안에_만들고_지운다(@TempDir Path workDir) throws Exception {
        ProcessResult result = runner.run(List.of(JAVA, "-version"), workDir, null, TIMEOUT);

        assertThat(result.succeeded()).isTrue();
        try (var files = Files.list(workDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void 출력을_버리고_실행해도_종료_코드와_시간은_돌려준다() throws Exception {
        ProcessResult result = runner.runDiscardingOutput(List.of(JAVA, "-version"), null, null, TIMEOUT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEmpty();
        assertThat(result.elapsedNanos()).isGreaterThan(0);
    }

    @Test
    void 표준_입력은_바로_닫혀서_입력을_읽는_프로그램이_기다리지_않는다(@TempDir Path workDir) throws Exception {
        Path source = workDir.resolve("ReadStdin.java");
        Files.writeString(source, """
                class ReadStdin {
                    public static void main(String[] args) throws Exception {
                        System.exit(System.in.read() == -1 ? 0 : 1);
                    }
                }
                """);

        ProcessResult result = runner.runDiscardingOutput(List.of(JAVA, source.toString()), workDir, null, TIMEOUT);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void 없는_실행_파일은_IOException을_던진다() {
        assertThatThrownBy(() -> runner.run(List.of("no-such-compiler-executable"), null, null, TIMEOUT))
                .isInstanceOf(IOException.class);
    }

    @Test
    void PATH_맨_앞에_디렉터리를_추가한다() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "existing"));

        ProcessRunner.prependToPath(env, "tooldir", false);

        assertThat(env).containsEntry("PATH", "tooldir" + File.pathSeparator + "existing");
    }

    @Test
    void Windows처럼_이름이_Path로_들어있으면_새_키를_만들지_않고_그_키를_갱신한다() {
        Map<String, String> env = new HashMap<>(Map.of("Path", "existing"));

        ProcessRunner.prependToPath(env, "tooldir", true);

        assertThat(env).containsOnlyKeys("Path");
        assertThat(env).containsEntry("Path", "tooldir" + File.pathSeparator + "existing");
    }

    @Test
    void PATH가_없으면_새로_만든다() {
        Map<String, String> env = new HashMap<>();

        ProcessRunner.prependToPath(env, "tooldir", true);

        assertThat(env).containsEntry("PATH", "tooldir");
    }

    @Test
    void 줄바꿈을_LF로_통일한다() {
        assertThat(ProcessRunner.normalizeNewlines("a\r\nb\nc\r\n")).isEqualTo("a\nb\nc\n");
    }
}
