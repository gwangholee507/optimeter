package com.compareopt.optvisualizer.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컴파일러가 최적화 remark를 내게 하는 인자와, 그 출력을 읽는 방법. GCC식 명령줄을 쓰는 컴파일러끼리도 이 부분은 서로 다르다.
 *
 * remark 인자는 진단 출력만 늘리고 만들어지는 코드는 바꾸지 않는다 (CompilerServiceTest가 실제 컴파일러로 확인한다).
 * 그래서 화면에 보여줄 어셈블리를 만드는 명령에 그대로 붙여서, remark와 어셈블리가 같은 컴파일의 결과가 되게 한다.
 */
enum RemarkFormat {

    /**
     * input.c:10:18: remark: 'square' inlined into 'sum' with (cost=-30, threshold=337) [-Rpass=inline]
     *
     * -Rpass-analysis는 켜지 않는다. 패스마다 명령어 수가 어떻게 바뀌었는지까지 나와서 짧은 코드도 수백 줄이 된다.
     * -fno-caret-diagnostics로 소스 인용 줄을 끄지도 않는다. 컴파일 에러에서도 인용이 사라지기 때문이다.
     * 인용 줄은 아래 정규식에 맞지 않아서 저절로 걸러진다.
     */
    CLANG(List.of("-Rpass=.*", "-Rpass-missed=.*"),
            Pattern.compile("^(.+?):(\\d+):(\\d+): remark: (.*) \\[-Rpass(-missed)?=([^\\]]+)]$")) {
        // 최적화라기보다 코드 생성 과정의 기록이라 초보자에게는 잡음인 패스
        private static final Set<String> NOISY_PASSES = Set.of(
                "regalloc",        // 레지스터 할당 비용 통계. 함수마다 한 줄씩 나온다
                "stack-protector", // 스택 보호 코드 삽입. 최적화가 아니고 O0에서도 나온다
                "inline-cost");    // 인라이닝 비용 계산 실패. 같은 자리에 inline 패스의 remark가 따로 나온다

        @Override
        Optional<Parsed> parseLine(Matcher m) {
            String pass = m.group(6);
            if (NOISY_PASSES.contains(pass)) {
                return Optional.empty();
            }
            OptRemark.Kind kind = m.group(5) == null ? OptRemark.Kind.APPLIED : OptRemark.Kind.MISSED;
            return Optional.of(new Parsed(m.group(1), kind, Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), pass, m.group(4).strip(), RemarkTopic.forClangPass(pass)));
        }
    },

    /**
     * input.c:9:23: optimized: loop vectorized using 16 byte vectors
     * input.c:9:23: missed: couldn't vectorize loop
     *
     * -fopt-info-all은 쓰지 않는다. note까지 나와서 벡터화 분석 과정이 수백 줄씩 붙는다.
     */
    GCC(List.of("-fopt-info-optimized-missed"),
            Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?: (optimized|missed): (.*)$")) {
        @Override
        Optional<Parsed> parseLine(Matcher m) {
            OptRemark.Kind kind = m.group(4).equals("optimized") ? OptRemark.Kind.APPLIED : OptRemark.Kind.MISSED;
            String message = m.group(5).strip();
            int column = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            return Optional.of(new Parsed(m.group(1), kind, Integer.parseInt(m.group(2)), column,
                    null, message, RemarkTopic.forGccMessage(message)));
        }
    };

    private final List<String> flags;
    private final Pattern linePattern;

    RemarkFormat(List<String> flags, Pattern linePattern) {
        this.flags = flags;
        this.linePattern = linePattern;
    }

    /** 어셈블리를 만드는 명령에 덧붙일 인자. */
    List<String> flags() {
        return flags;
    }

    abstract Optional<Parsed> parseLine(Matcher matchedLine);

    /**
     * 컴파일러 출력에서 사용자 소스 파일에 대한 remark만 뽑아 소스 줄 순서로 돌려준다.
     *
     * - 헤더 파일 안의 remark는 버린다. C++ 표준 라이브러리를 쓰면 대부분이 헤더 내부의 인라이닝 기록이고,
     *   화면에서 가리킬 사용자 코드의 줄이 없다.
     * - 같은 위치에 같은 내용이 여러 번 나오면 하나로 합치고 횟수를 센다
     *   (루프를 여러 패스가 다시 볼 때마다 같은 remark가 반복된다).
     * - 같은 위치의 remark끼리는 컴파일러가 낸 순서를 유지한다.
     */
    List<OptRemark> parse(String compilerOutput, String sourceFile) {
        Map<Parsed, Integer> counts = new LinkedHashMap<>();
        for (String line : compilerOutput.split("\n")) {
            Matcher m = linePattern.matcher(line);
            if (!m.matches()) {
                continue;
            }
            parseLine(m)
                    .filter(parsed -> parsed.file().equals(sourceFile))
                    .ifPresent(parsed -> counts.merge(parsed, 1, Integer::sum));
        }

        List<OptRemark> remarks = new ArrayList<>();
        counts.forEach((parsed, count) -> remarks.add(parsed.toRemark(count)));
        remarks.sort(Comparator.comparingInt(OptRemark::line).thenComparingInt(OptRemark::column));
        return remarks;
    }

    record Parsed(String file, OptRemark.Kind kind, int line, int column, String pass, String message,
                  Optional<RemarkTopic> topic) {

        OptRemark toRemark(int count) {
            return new OptRemark(kind, line, column, pass,
                    topic.map(RemarkTopic::label).orElse(null),
                    topic.map(RemarkTopic::description).orElse(null),
                    message, count);
        }
    }
}
