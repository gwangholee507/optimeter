package com.compareopt.optvisualizer.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * clang과 GCC처럼 `-S`, `-c`, `-O2` 같은 GCC식 명령줄을 쓰는 컴파일러.
 * 언어와 상관없이 같은 규칙이라, 실행 파일만 다른 C++(clang++, g++)도 이 구현을 함께 쓸 수 있다.
 * 최적화 remark를 내게 하는 인자와 출력 형식만 clang과 GCC가 달라서 RemarkFormat으로 받는다.
 */
final class GccStyleToolchain implements Toolchain {

    // 각 레벨이 실제로 뭘 하는지 초보자용으로 풀어쓴 설명 (화면의 열 제목 옆 툴팁)
    private static final List<OptLevel> LEVELS = List.of(
            level("O0", "최적화 없음(기본값). 소스 코드와 어셈블리가 거의 한 줄씩 대응돼서 디버깅하기 가장 쉽지만, "
                    + "실행 속도는 가장 느립니다."),
            level("O1", "기본적인 최적화. 죽은 코드 제거, 간단한 상수 계산(상수 폴딩) 등 컴파일 시간을 크게 "
                    + "늘리지 않는 선에서 적용합니다."),
            level("O2", "가장 널리 쓰이는 \"권장\" 수준. 함수 인라이닝, 루프 최적화, 명령어 재배치 등 대부분의 "
                    + "안전한 최적화를 적극적으로 적용합니다. 대부분의 릴리즈 빌드가 이 옵션을 씁니다."),
            level("O3", "O2에 루프 벡터화(SIMD) 등 더 공격적인 최적화를 추가합니다. 바이너리 크기가 커질 수 있고, "
                    + "코드에 따라 O2보다 항상 빠른 것은 아닙니다."));

    private final boolean requireGnu;
    private final RemarkFormat remarkFormat;

    /**
     * @param requireGnu   true면 진짜 GNU GCC만 인정한다 (clang의 별칭인 gcc는 건너뜀)
     * @param remarkFormat 이 컴파일러가 최적화 remark를 내는 방식
     */
    GccStyleToolchain(boolean requireGnu, RemarkFormat remarkFormat) {
        this.requireGnu = requireGnu;
        this.remarkFormat = remarkFormat;
    }

    @Override
    public List<OptLevel> levels() {
        return LEVELS;
    }

    @Override
    public List<String> versionArgs() {
        return List.of("--version");
    }

    /**
     * GNU GCC여야 하는 계열이면 버전 출력에 "clang"이 없어야 인정한다.
     *
     * macOS는 `/usr/bin/gcc`가 사실 Apple clang의 별칭이라 `gcc --version`이 정상 종료되면서
     * "Apple clang version ..."을 출력한다. 그대로 두면 GCC를 골랐는데 실제로는 clang이 실행되는
     * 오탐이 생긴다.
     */
    @Override
    public Optional<String> versionMismatch(String versionOutput) {
        if (requireGnu && versionOutput.toLowerCase(Locale.ROOT).contains("clang")) {
            return Optional.of("GNU GCC가 아니라 clang의 별칭으로 보입니다.");
        }
        return Optional.empty();
    }

    /**
     * {compiler} -S -O{n} {remark 인자} input.c -o out.s — 어셈블리와 함께 최적화 remark를 출력한다.
     * {compiler} -c out.s -o out.o — 방금 만든 어셈블리를 어셈블만 한다. 링크하지 않으므로 main()이 없어도 된다.
     *
     * 오브젝트를 소스부터 다시 컴파일하면 레벨마다 컴파일을 두 번 하게 된다. 어셈블만 해도 오브젝트는 같다
     * (clang과 GCC 모두 O0~O3에서 크기가 같은 것을 확인함. GCC는 원래 내부에서 어셈블리를 거쳐 오브젝트를 만든다).
     */
    @Override
    public List<List<String>> codegenCommands(OptLevel level, String sourceFile, String asmFile, String objFile) {
        List<String> asmArgs = new ArrayList<>(List.of("-S", flag(level)));
        asmArgs.addAll(remarkFormat.flags());
        asmArgs.addAll(List.of(sourceFile, "-o", asmFile));
        return List.of(
                asmArgs,
                List.of("-c", asmFile, "-o", objFile));
    }

    @Override
    public Optional<List<OptRemark>> remarks(String compilerOutput, String sourceFile) {
        return Optional.of(remarkFormat.parse(compilerOutput, sourceFile));
    }

    /**
     * {compiler} out.o -o out.exe — codegenCommands가 만든 오브젝트를 링크만 한다. main()이 있어야 성공한다.
     * C++도 clang++/g++ 드라이버로 링크하므로 표준 라이브러리가 함께 링크된다.
     */
    @Override
    public List<String> executableArgs(OptLevel level, String sourceFile, String objFile, String outputFile) {
        return List.of(objFile, "-o", outputFile);
    }

    /** 레벨 id가 곧 플래그 이름이라 (O2 → -O2) 화면의 열 제목도 플래그 그대로 보여준다. */
    private static OptLevel level(String id, String description) {
        return new OptLevel(id, "-" + id, description);
    }

    private static String flag(OptLevel level) {
        return "-" + level.id();
    }
}
