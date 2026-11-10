package com.compareopt.optvisualizer.toolchain;

/**
 * 컴파일러가 한 레벨을 컴파일하면서 남긴 최적화 기록(remark) 하나.
 * "이 줄에서 무슨 최적화를 했다" 또는 "하려다 못 했다"를 컴파일러가 직접 알려준 것이라, 어셈블리 모양으로 추정한 것이 아니다.
 *
 * @param kind             최적화를 적용했는지, 시도했지만 못 했는지
 * @param line             사용자 소스 코드의 줄 번호 (1부터)
 * @param column           그 줄 안의 칸 번호 (1부터). 컴파일러가 알려주지 않으면 0
 * @param pass             컴파일러가 붙인 최적화 패스 이름 (예: "loop-vectorize"). GCC는 알려주지 않아서 null
 * @param topicLabel       이 remark가 속한 최적화 종류의 한국어 이름 (예: "루프 벡터화"). 모르는 종류면 null
 * @param topicDescription 그 최적화가 무엇을 하는지 초보자용으로 풀어쓴 설명. 모르는 종류면 null
 * @param message          컴파일러가 출력한 원문 메시지
 * @param count            같은 위치에 같은 메시지가 몇 번 나왔는지 (한 줄로 합쳐서 보여준다)
 */
public record OptRemark(Kind kind, int line, int column, String pass, String topicLabel, String topicDescription,
                        String message, int count) {

    public enum Kind {
        /** 최적화를 적용했다 (clang의 -Rpass, GCC의 optimized) */
        APPLIED,
        /** 최적화를 시도했지만 하지 못했다 (clang의 -Rpass-missed, GCC의 missed) */
        MISSED
    }
}
