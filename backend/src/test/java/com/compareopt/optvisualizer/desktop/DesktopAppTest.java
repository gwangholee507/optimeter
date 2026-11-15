package com.compareopt.optvisualizer.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ApplicationContextException;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 모드에서 AWT(Dock, 브라우저, 대화상자)를 쓰지 않는 판단 부분만 확인한다.
 * AWT 부분은 CI처럼 화면이 없는 환경에서 돌릴 수 없어서, 릴리스 워크플로가 실제 앱을 띄워보는 것으로 확인한다.
 */
class DesktopAppTest {

    @Test
    void 기본값으로는_앱_모드가_꺼져_있다() {
        assertThat(DesktopApp.isEnabled()).isFalse();
    }

    @Test
    void 포트_충돌은_감싸진_예외_안에_있어도_알아본다() {
        // Spring Boot는 포트 충돌을 "webServerStartStop 빈을 시작하지 못함" 예외로 감싸서 던진다
        Exception wrapped = new ApplicationContextException("Failed to start bean 'webServerStartStop'",
                new PortInUseException(8080));

        assertThat(DesktopApp.isPortInUse(wrapped)).isTrue();
        assertThat(DesktopApp.isPortInUse(new IllegalStateException("다른 이유"))).isFalse();
    }

    @Test
    void 포트를_직접_지정했으면_빈_포트로_바꾸지_않도록_알아본다() {
        assertThat(DesktopApp.hasExplicitPort(new String[]{"--server.port=18080"})).isTrue();
        assertThat(DesktopApp.hasExplicitPort(new String[]{"--optimeter.desktop.open-browser=false"})).isFalse();
        assertThat(DesktopApp.hasExplicitPort(new String[]{})).isFalse();
    }

    @Test
    void 인자를_뒤에_덧붙인다() {
        assertThat(DesktopApp.withArg(new String[]{"--a=1"}, "--server.port=0"))
                .containsExactly("--a=1", "--server.port=0");
    }

    @Test
    void 화면_주소는_localhost와_실제_포트로_만든다() {
        assertThat(DesktopApp.localUrl(54321)).isEqualTo(URI.create("http://localhost:54321/"));
    }

    @Test
    void 로그_파일은_macOS_로그_폴더_아래에_둔다() {
        assertThat(DesktopApp.logFile("/Users/tester"))
                .isEqualTo(Path.of("/Users/tester", "Library", "Logs", "Optimeter", "optimeter.log"));
    }

    @Test
    void 시작_실패_문구에는_가장_안쪽_원인과_로그_위치를_넣는다() {
        Exception e = new IllegalStateException("겉", new RuntimeException("속", new IllegalArgumentException("진짜 원인")));

        assertThat(DesktopApp.startupErrorMessage(e, "/tmp/optimeter.log"))
                .contains("시작하지 못했습니다", "진짜 원인", "/tmp/optimeter.log")
                .doesNotContain("겉");
    }

    @Test
    void 원인_메시지가_없으면_예외_이름을_보여준다() {
        assertThat(DesktopApp.startupErrorMessage(new NullPointerException(), null))
                .contains("NullPointerException")
                .doesNotContain("로그 파일");
    }
}
