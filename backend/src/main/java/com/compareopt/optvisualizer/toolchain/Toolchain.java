package com.compareopt.optvisualizer.toolchain;

import java.util.List;
import java.util.Optional;

/**
 * 컴파일러 계열마다 달라지는 "명령줄 만드는 법".
 *
 * CompilerService는 여기서 받은 인자로 컴파일러를 실행하기만 하고, 임시 디렉터리, 시간 제한,
 * 실행 시간 측정처럼 모든 컴파일러에 공통인 부분은 CompilerService가 그대로 맡는다.
 * 돌려주는 인자 목록에는 실행 파일 경로를 넣지 않는다 (경로는 CompilerRegistry가 찾아서 맨 앞에 붙인다).
 * 파일 이름은 모두 작업 디렉터리 기준의 상대 경로다.
 */
public interface Toolchain {

    /** 비교할 최적화 레벨 목록. 결과도 이 순서대로 만들어지고, 화면은 첫 번째 레벨을 비교 기준으로 삼는다. */
    List<OptLevel> levels();

    /** 설치 여부를 확인할 때 실행 파일 뒤에 붙일 인자. 종료 코드가 0이면 일단 실행 가능한 것으로 본다. */
    List<String> versionArgs();

    /**
     * 버전 출력을 보고 이 계열의 컴파일러가 맞는지 확인한다.
     * 맞으면 empty, 아니면 그 후보를 건너뛰는 이유를 돌려준다 (서버 시작 로그에 남는다).
     */
    Optional<String> versionMismatch(String versionOutput);

    /**
     * 이 소스 코드를 컴파일할 때 쓸 Toolchain을 돌려준다.
     * 코드 모양에 따라 명령줄이 달라지는 계열만 재정의한다 (예: rustc는 main 유무로 크레이트 종류가 갈린다).
     */
    default Toolchain forSource(String sourceCode) {
        return this;
    }

    /**
     * 컴파일 실패 출력을 보고 forSource의 선택이 틀렸으면 다시 컴파일할 Toolchain을 돌려준다.
     * forSource는 코드 모양만 보고 고르므로, 컴파일러의 에러로 바로잡을 수 있는 계열만 재정의한다.
     */
    default Optional<Toolchain> correctedBy(String compilerOutput) {
        return Optional.empty();
    }

    /**
     * 어셈블리 파일과 크기 비교용 오브젝트 파일을 만드는 명령들의 인자. 앞에서부터 차례로 실행한다.
     *
     * 첫 번째 명령이 어셈블리를 만들고, 이 명령이 실패하면 그 레벨은 컴파일 실패가 된다.
     * 한 번의 실행으로 둘 다 만들 수 있는 컴파일러는 명령을 하나만 돌려준다.
     * main() 같은 진입점이 없는 코드에서도 성공해야 한다.
     */
    List<List<String>> codegenCommands(OptLevel level, String sourceFile, String asmFile, String objFile);

    /** 실행 시간 측정용 실행 파일을 만드는 인자. 진입점이 없으면 실패해도 된다 (실행 측정만 건너뜀). */
    List<String> executableArgs(OptLevel level, String sourceFile, String outputFile);

    /**
     * 프로그램의 종료 코드를 보고 실행 시간을 믿을 수 없으면 그 이유를 돌려준다.
     * 기본은 종료 코드를 따지지 않는다. C는 계산 결과를 main의 반환값으로 쓰는 코드가 흔해서
     * 0이 아니어도 정상 실행일 수 있기 때문이다.
     */
    default Optional<String> executionFailure(int exitCode) {
        return Optional.empty();
    }

    /** 컴파일은 성공했지만 결과를 볼 때 알아야 할 내용이 있으면 돌려준다 (화면의 열에 안내 문구로 표시된다). */
    default Optional<String> assemblyNotice(String assembly) {
        return Optional.empty();
    }
}
