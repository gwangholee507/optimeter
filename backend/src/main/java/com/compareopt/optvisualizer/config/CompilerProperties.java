package com.compareopt.optvisualizer.config;

import com.compareopt.optvisualizer.platform.HostOs;
import com.compareopt.optvisualizer.toolchain.Language;
import com.compareopt.optvisualizer.toolchain.ToolchainKind;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * application.yml의 compiler.options 목록을 바인딩하는 설정 클래스.
 *
 * 컴파일러 하나(예: gcc)당 여러 후보 실행 파일(candidates)을 등록해둘 수 있다.
 * Homebrew로 설치한 GCC는 "gcc-16"처럼 버전이 붙은 이름으로 설치되고, Windows의 MSYS2는
 * PATH에 등록되지 않는 C:\msys64 아래에 설치되는 등 환경마다 실제 실행 파일 위치가 달라지기 때문에,
 * 후보를 순서대로 시도해서 처음 성공하는 것을 사용한다 (CompilerRegistry 참고).
 */
@Component
@ConfigurationProperties(prefix = "compiler")
public class CompilerProperties {

    private List<Option> options = new ArrayList<>();
    /**
     * PATH에 없어도 이름만 적힌 후보(예: "gcc-16")를 찾아볼 디렉터리들. 키는 HostOs.configKey().
     * Finder에서 연 macOS 앱은 셸 설정 파일을 거치지 않아 PATH에 Homebrew 디렉터리가 없기 때문에 필요하다.
     */
    private Map<String, List<String>> searchDirs = new HashMap<>();

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
    }

    public Map<String, List<String>> getSearchDirs() {
        return searchDirs;
    }

    public void setSearchDirs(Map<String, List<String>> searchDirs) {
        this.searchDirs = searchDirs;
    }

    /** 주어진 OS에서 이름만 적힌 후보를 찾아볼 디렉터리 (빈 값은 건너뜀). */
    public List<String> searchDirsFor(HostOs os) {
        return nonBlank(searchDirs.getOrDefault(os.configKey(), List.of()));
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    public static class Option {
        /** 프론트엔드가 요청 시 넘기는 식별자 (예: "clang", "gcc") */
        private String id;
        /** 프론트엔드 드롭다운에 보여줄 이름 */
        private String label;
        /** 사용자 코드의 언어 (예: c). 소스 파일 이름이 이 값으로 정해진다. 필수. */
        private Language language;
        /** 컴파일러 계열 (예: clang, gcc). 명령줄 규칙과 설치 확인 방법이 이 값으로 정해진다. 필수. */
        private ToolchainKind toolchain;
        /** 모든 OS에서 시도해볼 후보들 (순서대로 시도). PATH에 있는 실행 파일 이름 또는 절대 경로. */
        private List<String> candidates = new ArrayList<>();
        /** 특정 OS에서만 추가로 시도할 후보들. 키는 HostOs.configKey() ("windows", "macos", "linux"). */
        private Map<String, List<String>> osCandidates = new HashMap<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Language getLanguage() {
            return language;
        }

        public void setLanguage(Language language) {
            this.language = language;
        }

        public ToolchainKind getToolchain() {
            return toolchain;
        }

        public void setToolchain(ToolchainKind toolchain) {
            this.toolchain = toolchain;
        }

        public List<String> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<String> candidates) {
            this.candidates = candidates;
        }

        public Map<String, List<String>> getOsCandidates() {
            return osCandidates;
        }

        public void setOsCandidates(Map<String, List<String>> osCandidates) {
            this.osCandidates = osCandidates;
        }

        /**
         * 주어진 OS에서 시도할 후보를 순서대로 반환한다: 공통 후보 → 그 OS 전용 후보.
         * 빈 값은 건너뛴다 (application.yml의 "${OPTVIS_CLANG:}"처럼 환경 변수가 없을 때 빈 문자열이 되는 후보).
         */
        public List<String> candidatesFor(HostOs os) {
            return candidatesFor(os, List.of());
        }

        /**
         * candidatesFor(os)와 같되, 이름만 적힌 공통 후보 바로 뒤에 searchDirs 안의 같은 이름을 끼워 넣는다.
         * 예) [gcc-16, gcc-15] + [/opt/homebrew/bin] → gcc-16, /opt/homebrew/bin/gcc-16, gcc-15, /opt/homebrew/bin/gcc-15
         * 후보마다 바로 뒤에 넣는 이유: 어느 디렉터리에 있든 앞에 적힌(= 더 새 버전) 후보가 먼저 잡히게 하려고.
         */
        public List<String> candidatesFor(HostOs os, List<String> searchDirs) {
            List<String> result = new ArrayList<>();
            for (String candidate : nonBlank(candidates)) {
                result.add(candidate);
                if (isBareName(candidate)) {
                    for (String dir : searchDirs) {
                        result.add(join(dir, candidate));
                    }
                }
            }
            result.addAll(nonBlank(osCandidates.getOrDefault(os.configKey(), List.of())));
            return result;
        }

        /** 경로 구분자가 없는 실행 파일 이름인지 (PATH에서 찾는 후보). */
        private static boolean isBareName(String candidate) {
            return candidate.indexOf('/') < 0 && candidate.indexOf('\\') < 0;
        }

        private static String join(String dir, String name) {
            return dir.endsWith("/") || dir.endsWith("\\") ? dir + name : dir + "/" + name;
        }
    }
}
