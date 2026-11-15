package com.compareopt.optvisualizer.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.Icon;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.desktop.AppReopenedListener;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * macOS 앱(.dmg)으로 실행될 때의 동작.
 *
 * 터미널에서 `java -jar`로 띄울 때와 달리 앱은 Finder에서 열리므로 터미널도 창도 없다.
 * 아무것도 하지 않으면 서버가 보이지 않게 떠 있기만 하고, 끌 방법도 없다. 그래서 이 모드에서는
 * - AWT를 켜서 Dock 아이콘을 띄운다. Dock 메뉴의 "종료"나 Cmd+Q로 끌 수 있다 (System.exit → 서버 정상 종료).
 * - 서버가 뜨면 브라우저로 화면을 열고, Dock 아이콘을 다시 누르면 또 열어준다.
 * - 기본 포트를 다른 프로그램이 쓰고 있으면 빈 포트로 다시 띄운다. 주소는 브라우저를 열 때 알아서 맞춘다.
 * - 그래도 시작하지 못하면 대화상자로 알린다. 안 그러면 Dock 아이콘이 잠깐 떴다가 아무 말 없이 사라진다.
 * - 로그를 파일(macOS는 ~/Library/Logs/Optimeter/optimeter.log)에 남긴다. 콘솔 출력은 볼 수 없으므로.
 *
 * jpackage로 만든 앱의 실행 옵션에 -Doptimeter.desktop=true 를 넣어서 켠다 (packaging/macos/build-dmg.sh).
 * jar로 실행할 때는 이 클래스를 거치지 않는다.
 */
public final class DesktopApp {

    /** 이 값이 true인 시스템 속성이 있으면 앱 모드로 실행한다. */
    public static final String ENABLED_PROPERTY = "optimeter.desktop";
    /** false로 주면 서버가 떠도 브라우저를 열지 않는다 (CI에서 앱을 띄워볼 때 사용). */
    static final String OPEN_BROWSER_PROPERTY = "optimeter.desktop.open-browser";

    private static final Logger log = LoggerFactory.getLogger(DesktopApp.class);
    /** macOS 경고 대화상자의 아이콘 크기 (pt) */
    private static final int DIALOG_ICON_SIZE = 64;

    /** 서버가 뜬 뒤의 화면 주소. 뜨기 전에 Dock 아이콘을 누르면 null이라 아무것도 하지 않는다. */
    private static volatile URI appUrl;

    private DesktopApp() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static void run(Class<?> source, String[] args) {
        // Spring Boot는 java.awt.headless가 비어 있으면 true로 정해서 AWT(Dock 아이콘, 대화상자)를 끈다
        System.setProperty("java.awt.headless", "false");
        // 지정하지 않으면 AWT가 메인 클래스 이름(Spring Boot의 JarLauncher)을 앱 이름으로 써서
        // 메뉴 막대에 "JarLauncher 종료"처럼 나온다
        System.setProperty("apple.awt.application.name", "Optimeter");
        if (System.getProperty("logging.file.name") == null) {
            System.setProperty("logging.file.name", logFile(System.getProperty("user.home")).toString());
        }
        // 서버가 뜨는 몇 초 동안에도 Dock 아이콘이 반응하고 종료할 수 있도록 먼저 켠다
        listenForReopen();

        ConfigurableApplicationContext context;
        try {
            context = start(source, args);
        } catch (Throwable e) {
            log.error("Optimeter를 시작하지 못했습니다.", e);
            showError(startupErrorMessage(e, System.getProperty("logging.file.name")));
            System.exit(1);
            return;
        }

        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        appUrl = localUrl(port);
        log.info("화면 주소: {}", appUrl);
        if (context.getEnvironment().getProperty(OPEN_BROWSER_PROPERTY, Boolean.class, true)) {
            openBrowser(appUrl);
        }
    }

    /** 서버를 띄운다. 포트를 직접 지정하지 않았는데 기본 포트가 쓰이고 있으면 빈 포트로 한 번 더 시도한다. */
    private static ConfigurableApplicationContext start(Class<?> source, String[] args) {
        try {
            return SpringApplication.run(source, args);
        } catch (RuntimeException e) {
            if (!isPortInUse(e) || hasExplicitPort(args)) {
                throw e;
            }
            log.warn("기본 포트를 다른 프로그램이 쓰고 있어서 빈 포트로 다시 시작합니다.");
            return SpringApplication.run(source, withArg(args, "--server.port=0"));
        }
    }

    static boolean isPortInUse(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PortInUseException) {
                return true;
            }
        }
        return false;
    }

    static boolean hasExplicitPort(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--server.port="));
    }

    static String[] withArg(String[] args, String extra) {
        String[] result = Arrays.copyOf(args, args.length + 1);
        result[args.length] = extra;
        return result;
    }

    static URI localUrl(int port) {
        return URI.create("http://localhost:" + port + "/");
    }

    /**
     * 앱 모드의 로그 파일 위치: ~/Library/Logs/Optimeter/optimeter.log (macOS의 "콘솔" 앱에서 바로 볼 수 있는 곳).
     * 앱 모드는 macOS 앱에서만 쓰므로 macOS 위치로 고정한다. 다른 OS용 앱을 만들게 되면 여기를 OS별로 나눈다.
     */
    static Path logFile(String userHome) {
        return Path.of(userHome, "Library", "Logs", "Optimeter", "optimeter.log");
    }

    /** 시작 실패 대화상자에 보여줄 문구: 가장 안쪽 원인의 메시지 + 로그 파일 위치. */
    static String startupErrorMessage(Throwable e, String logFile) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String reason = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        StringBuilder message = new StringBuilder("Optimeter를 시작하지 못했습니다.\n\n").append(reason);
        if (logFile != null) {
            message.append("\n\n자세한 내용은 로그 파일을 확인하세요:\n").append(logFile);
        }
        return message.toString();
    }

    /**
     * Dock 아이콘을 다시 누르거나 앱을 다시 열면 화면을 다시 띄운다 (브라우저 탭을 닫은 경우).
     * AWT를 처음 쓰는 곳이라 여기서 Dock 아이콘이 자리를 잡는다. AWT를 쓸 수 없는 환경이어도 서버는 띄운다.
     */
    private static void listenForReopen() {
        try {
            if (!Desktop.isDesktopSupported()) {
                log.warn("이 환경에서는 Dock 연동을 쓸 수 없습니다.");
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener((AppReopenedListener) event -> {
                    URI url = appUrl;
                    if (url != null) {
                        openBrowser(url);
                    }
                });
            }
        } catch (Throwable e) {
            log.warn("Dock 연동을 켜지 못했습니다: {}", e.toString());
        }
    }

    private static void openBrowser(URI url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                log.info("브라우저로 {} 을(를) 엽니다.", url);
                Desktop.getDesktop().browse(url);
                return;
            }
        } catch (Exception e) {
            log.warn("브라우저를 열지 못했습니다: {}", e.toString());
        }
        log.warn("브라우저에서 {} 을(를) 직접 열어주세요.", url);
    }

    private static void showError(String message) {
        try {
            JOptionPane.showMessageDialog(null, message, "Optimeter", JOptionPane.ERROR_MESSAGE, appIcon());
        } catch (Throwable e) {
            log.warn("오류 대화상자를 띄우지 못했습니다: {}", e.toString());
        }
    }

    /**
     * 대화상자에 보여줄 앱 아이콘(로고). 그냥 ERROR_MESSAGE로 띄우면 macOS는 빨간 정지 표지판을 크게 그리고
     * 로고는 귀퉁이에 작게만 붙여서, 로고가 아닌 그림이 뜬 것처럼 보인다.
     * Dock 아이콘 이미지를 64×64로 그린다 (Retina 화면에서는 큰 해상도 쪽을 골라 그려서 흐려지지 않음).
     * 못 구하면 null → 기본 아이콘.
     */
    private static Icon appIcon() {
        try {
            if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                return null;
            }
            Image image = Taskbar.getTaskbar().getIconImage();
            if (image == null) {
                return null;
            }
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    g.drawImage(image, x, y, DIALOG_ICON_SIZE, DIALOG_ICON_SIZE, null);
                }

                @Override
                public int getIconWidth() {
                    return DIALOG_ICON_SIZE;
                }

                @Override
                public int getIconHeight() {
                    return DIALOG_ICON_SIZE;
                }
            };
        } catch (Throwable e) {
            return null;
        }
    }
}
