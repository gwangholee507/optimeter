package com.compareopt.optvisualizer.service;

import com.compareopt.optvisualizer.config.CompilerProperties;
import com.compareopt.optvisualizer.platform.HostOs;
import com.compareopt.optvisualizer.platform.ProcessResult;
import com.compareopt.optvisualizer.platform.ProcessRunner;
import com.compareopt.optvisualizer.toolchain.Language;
import com.compareopt.optvisualizer.toolchain.Toolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * application.yml에 등록된 컴파일러 후보들 중 이 서버에서 실제로 실행 가능한 것을
 * 서버 시작 시 한 번 탐지해두는 레지스트리.
 *
 * 사용자가 요청 바디에 넘긴 컴파일러 id를 그대로 실행 파일 경로로 쓰지 않고
 * 반드시 이 레지스트리를 통해서만 실제 경로로 변환한다 — 임의 문자열이 그대로
 * ProcessBuilder 커맨드로 흘러들어가는 것(명령어 인젝션)을 막기 위한 화이트리스트.
 */
@Component
public class CompilerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompilerRegistry.class);
    // 후보가 없으면 즉시 실패하므로 이 시간은 "실행은 되는데 응답이 없는" 경우에만 쓰인다.
    // Windows에서는 큰 실행 파일의 첫 실행이 백신 검사나 디스크 읽기로 10초를 넘길 수 있어 넉넉하게 잡는다.
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner processRunner;
    private final List<ResolvedCompiler> compilers = new ArrayList<>();

    public CompilerRegistry(CompilerProperties properties, ProcessRunner processRunner) {
        this.processRunner = processRunner;
        HostOs hostOs = HostOs.current();
        for (CompilerProperties.Option option : properties.getOptions()) {
            requireLanguageAndToolchain(option);
            Toolchain toolchain = option.getToolchain().toolchain();
            List<String> candidates = option.candidatesFor(hostOs);
            String resolvedPath = probeCandidates(toolchain, candidates);
            boolean available = resolvedPath != null;
            if (available) {
                log.info("컴파일러 '{}'({}) → {}", option.getId(), option.getLabel(), resolvedPath);
            } else {
                log.warn("컴파일러 '{}'({})를 찾을 수 없습니다. 후보: {}", option.getId(), option.getLabel(), candidates);
            }
            compilers.add(new ResolvedCompiler(
                    option.getId(), option.getLabel(), option.getLanguage(), toolchain, resolvedPath, available));
        }
    }

    /**
     * language나 toolchain이 빠진 설정은 서버를 띄우는 단계에서 바로 알린다.
     * 기본값을 두면 새 언어를 추가하면서 빠뜨렸을 때 요청마다 엉뚱한 언어로 컴파일되어 원인을 찾기 어렵다.
     */
    private static void requireLanguageAndToolchain(CompilerProperties.Option option) {
        if (option.getLanguage() == null || option.getToolchain() == null) {
            throw new IllegalStateException(
                    "compiler.options의 '" + option.getId() + "'에 language와 toolchain을 지정해야 합니다.");
        }
    }

    /** 후보 실행 파일들을 순서대로 시도해서 처음으로 동작하는 것을 반환. 다 실패하면 null. */
    private String probeCandidates(Toolchain toolchain, List<String> candidates) {
        for (String candidate : candidates) {
            if (canRun(toolchain, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * `{executable} {versionArgs}`(예: `gcc-16 --version`)를 실행해보고, 종료 코드가 0인지 +
     * 그 출력을 toolchain이 자기 계열의 컴파일러로 인정하는지 확인한다
     * (예: gcc 계열은 clang의 별칭인 macOS의 gcc를 걸러낸다).
     */
    private boolean canRun(Toolchain toolchain, String executable) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(toolchain.versionArgs());
        try {
            ProcessResult result = processRunner.run(command, null, toolDirOf(executable), PROBE_TIMEOUT);
            if (!result.succeeded()) {
                return false;
            }
            Optional<String> mismatch = toolchain.versionMismatch(result.output());
            if (mismatch.isPresent()) {
                log.warn("'{}'를 건너뜁니다: {}", executable, mismatch.get());
                return false;
            }
            return true;
        } catch (IOException e) {
            return false; // 그 이름/경로의 실행 파일이 없음
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 절대 경로로 지정된 실행 파일이면 그 파일이 들어 있는 디렉터리를, PATH에서 이름으로 찾는 경우면 null을 반환.
     *
     * PATH에 등록되지 않은 위치의 컴파일러를 절대 경로로 실행할 때는 그 디렉터리를 자식 프로세스의
     * PATH에 넣어줘야 한다. Windows의 MinGW/MSYS2 GCC는 gcc.exe가 띄우는 cc1.exe가 필요한 DLL을
     * PATH에서 찾기 때문에, 이게 없으면 `--version`은 되는데 실제 컴파일은 실패한다.
     */
    static Path toolDirOf(String executable) {
        if (executable == null) {
            return null;
        }
        try {
            Path path = Path.of(executable);
            return path.isAbsolute() ? path.getParent() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public List<ResolvedCompiler> listAll() {
        return List.copyOf(compilers);
    }

    /** id로 사용 가능한 컴파일러를 찾는다. 없거나 사용 불가능하면 예외를 던진다 (컨트롤러에서 400으로 변환됨). */
    public ResolvedCompiler resolve(String id) {
        Optional<ResolvedCompiler> found = compilers.stream()
                .filter(c -> c.id().equals(id))
                .findFirst();

        if (found.isEmpty()) {
            throw new IllegalArgumentException("알 수 없는 컴파일러입니다: " + id);
        }
        ResolvedCompiler compiler = found.get();
        if (!compiler.available()) {
            throw new IllegalArgumentException("'" + compiler.label() + "'는 서버에 설치되어 있지 않습니다.");
        }
        return compiler;
    }

    public record ResolvedCompiler(String id, String label, Language language, Toolchain toolchain,
                                   String path, boolean available) {

        /** 이 컴파일러와, 이 컴파일러로 만든 프로그램을 실행할 때 PATH에 추가해야 하는 디렉터리 (없으면 null). */
        public Path toolDir() {
            return toolDirOf(path);
        }
    }
}
