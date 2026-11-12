package com.compareopt.optvisualizer.toolchain;

/**
 * 컴파일러 계열. application.yml의 compiler.options[].toolchain 값이다 (예: "clang", "gcc").
 *
 * 어떤 명령줄 규칙으로 컴파일러를 부르고, 설치 여부를 어떻게 확인할지가 이 값으로 정해진다.
 * 실행 파일 이름은 정하지 않으므로, 같은 계열이라도 언어에 따라 다른 실행 파일(clang / clang++)을
 * candidates에 적어주면 된다.
 */
public enum ToolchainKind {
    CLANG(new GccStyleToolchain(false, RemarkFormat.CLANG)),
    GCC(new GccStyleToolchain(true, RemarkFormat.GCC)),
    RUSTC(new RustcToolchain());

    private final Toolchain toolchain;

    ToolchainKind(Toolchain toolchain) {
        this.toolchain = toolchain;
    }

    public Toolchain toolchain() {
        return toolchain;
    }
}
