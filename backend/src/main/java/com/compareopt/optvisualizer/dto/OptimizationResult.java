package com.compareopt.optvisualizer.dto;

import com.compareopt.optvisualizer.toolchain.OptLevel;

public class OptimizationResult {

    private String level;            // 레벨 id (예: "O2")
    private String levelLabel;       // 화면의 열 제목 (예: "-O2")
    private String levelDescription; // 이 레벨이 하는 일을 풀어쓴 설명 (열 제목 옆 툴팁)
    private boolean success;
    private String assembly;       // -S 결과 (실패 시 null)
    private Long binarySizeBytes;  // 실행 파일 크기 (실패 시 null)
    private long compileTimeMs;
    private String errorMessage;   // 실패 시 컴파일러 stderr
    private String notice;         // 성공했지만 결과를 볼 때 알아야 할 내용 (예: 어셈블리에 함수가 없는 이유). 없으면 null

    private String compilerId;     // 이 결과를 만든 컴파일러 id (예: "gcc")
    private String compilerLabel;  // 화면 표시용 이름 (예: "GNU GCC")

    private Double executionTimeMs; // runBenchmark=true일 때 측정된 실행 시간(최솟값). 측정 안 했으면 null
    private String executionError;  // 실행 측정을 시도했지만 실패/불가능했던 이유 (예: main() 없음)

    public static OptimizationResult success(OptLevel level, String assembly, long binarySizeBytes, long compileTimeMs) {
        OptimizationResult r = new OptimizationResult();
        r.setLevel(level);
        r.success = true;
        r.assembly = assembly;
        r.binarySizeBytes = binarySizeBytes;
        r.compileTimeMs = compileTimeMs;
        return r;
    }

    public static OptimizationResult failure(OptLevel level, String errorMessage, long compileTimeMs) {
        OptimizationResult r = new OptimizationResult();
        r.setLevel(level);
        r.success = false;
        r.errorMessage = errorMessage;
        r.compileTimeMs = compileTimeMs;
        return r;
    }

    /** 결과마다 레벨의 이름과 설명을 함께 담아서, 화면이 컴파일러 목록과 따로 맞춰보지 않고 바로 그릴 수 있게 한다. */
    private void setLevel(OptLevel level) {
        this.level = level.id();
        this.levelLabel = level.label();
        this.levelDescription = level.description();
    }

    public String getLevel() {
        return level;
    }

    public String getLevelLabel() {
        return levelLabel;
    }

    public String getLevelDescription() {
        return levelDescription;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getAssembly() {
        return assembly;
    }

    public Long getBinarySizeBytes() {
        return binarySizeBytes;
    }

    public long getCompileTimeMs() {
        return compileTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public String getCompilerId() {
        return compilerId;
    }

    public String getCompilerLabel() {
        return compilerLabel;
    }

    public Double getExecutionTimeMs() {
        return executionTimeMs;
    }

    public String getExecutionError() {
        return executionError;
    }

    /** 컴파일에 어떤 컴파일러를 썼는지 붙여준다. success/failure 팩토리와 별개로 항상 채워짐. */
    public void setCompilerInfo(String compilerId, String compilerLabel) {
        this.compilerId = compilerId;
        this.compilerLabel = compilerLabel;
    }

    /** 실행 시간 측정 결과를 붙여준다. runBenchmark=false였다면 호출되지 않아 null로 남는다. */
    public void setExecutionResult(Double executionTimeMs, String executionError) {
        this.executionTimeMs = executionTimeMs;
        this.executionError = executionError;
    }
}
