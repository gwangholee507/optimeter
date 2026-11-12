package com.compareopt.optvisualizer.toolchain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * remark를 초보자가 알아볼 수 있는 최적화 종류로 묶고 한국어 이름과 설명을 붙인다.
 *
 * 자주 나오고 설명할 가치가 있는 종류만 둔다. 여기 없는 remark는 종류 없이 컴파일러의 원문만 보여준다.
 * clang은 remark마다 패스 이름을 알려주므로 그 이름으로 찾고, GCC는 패스 이름 없이 메시지만 주므로
 * 메시지에 항상 들어가는 낱말로 찾는다.
 */
enum RemarkTopic {
    INLINE("함수 인라이닝",
            "함수 호출을 없애고 호출한 자리에 함수 본문을 그대로 넣습니다. 호출 비용이 사라지고, 합쳐진 코드에 "
                    + "다른 최적화를 더 적용할 수 있게 됩니다.",
            List.of("inline"), List.of("inlin")),
    VECTORIZE("루프 벡터화",
            "값을 하나씩 처리하던 루프를 SIMD 명령으로 여러 개씩 묶어 처리하게 바꿉니다.",
            List.of("loop-vectorize"), List.of("vectoriz")),
    SLP("SLP 벡터화",
            "루프가 아니라 나란히 놓인 비슷한 연산 여러 개를 SIMD 명령 하나로 묶습니다.",
            List.of("slp-vectorizer"), List.of()),
    UNROLL("루프 펼치기",
            "루프 본문을 여러 번 복사해서 반복 횟수와 조건 검사를 줄입니다. 반복 횟수가 적으면 루프를 아예 없애기도 합니다.",
            List.of("loop-unroll", "loop-unroll-and-jam"), List.of("unroll")),
    GVN("중복 계산 제거",
            "같은 값을 다시 계산하거나 메모리에서 다시 읽는 부분을 찾아 앞에서 구한 값을 재사용합니다 (GVN).",
            List.of("gvn"), List.of()),
    LICM("루프 불변 코드 이동",
            "루프를 돌 때마다 결과가 같은 계산을 루프 밖으로 꺼냅니다 (LICM).",
            List.of("licm"), List.of()),
    LOOP_IDIOM("루프 관용구 치환",
            "메모리를 채우거나 복사하는 루프를 memset, memcpy 같은 라이브러리 호출로 바꿉니다.",
            List.of("loop-idiom"), List.of("library call")),
    LOOP_DELETE("루프 삭제",
            "결과에 영향을 주지 않는 루프를 통째로 지웁니다.",
            List.of("loop-delete"), List.of()),
    TAIL_CALL("꼬리 재귀 제거",
            "함수의 마지막에서 자기 자신을 부르는 재귀 호출을 루프로 바꿉니다.",
            List.of("tailcallelim"), List.of());

    private final String label;
    private final String description;
    private final List<String> clangPasses;
    private final List<String> gccKeywords;

    RemarkTopic(String label, String description, List<String> clangPasses, List<String> gccKeywords) {
        this.label = label;
        this.description = description;
        this.clangPasses = clangPasses;
        this.gccKeywords = gccKeywords;
    }

    String label() {
        return label;
    }

    String description() {
        return description;
    }

    static Optional<RemarkTopic> forClangPass(String pass) {
        for (RemarkTopic topic : values()) {
            if (topic.clangPasses.contains(pass)) {
                return Optional.of(topic);
            }
        }
        return Optional.empty();
    }

    /** 앞에 선언한 종류가 먼저 걸린다 (예: "Inlining ... vectorized_fn"은 인라이닝). */
    static Optional<RemarkTopic> forGccMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (RemarkTopic topic : values()) {
            if (topic.gccKeywords.stream().anyMatch(lower::contains)) {
                return Optional.of(topic);
            }
        }
        return Optional.empty();
    }
}
