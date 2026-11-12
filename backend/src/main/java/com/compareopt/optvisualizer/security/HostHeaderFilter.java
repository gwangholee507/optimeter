package com.compareopt.optvisualizer.security;

import com.compareopt.optvisualizer.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 요청의 Host 헤더가 이 서버를 가리키는 주소일 때만 통과시킨다 (DNS 리바인딩 방어).
 *
 * 서버가 127.0.0.1에서만 듣고 CORS 허용 설정이 없어도 막지 못하는 공격이 있다. 사용자가 방문한 사이트가
 * 자기 도메인(evil.example)의 DNS 응답을 127.0.0.1로 바꿔치기하면, 브라우저는 그 페이지의 스크립트가
 * http://evil.example:8080/api/compile 로 보내는 요청을 "같은 출처"로 보고 그대로 이 서버에 보낸다.
 * 이 API는 받은 코드를 컴파일하고 실행까지 하므로, 그 사이트가 사용자 PC에서 임의의 프로그램을 돌릴 수 있게 된다.
 *
 * 이때도 브라우저가 보내는 Host 헤더는 주소창의 이름(evil.example) 그대로라서, Host를 보면 구분할 수 있다.
 * - localhost와 IP 주소(127.0.0.1, 192.168.0.5, [::1])는 통과시킨다. IP 주소는 DNS를 거치지 않으므로
 *   바꿔치기할 수 없다. 그래서 --server.address=0.0.0.0으로 띄우고 다른 기기에서 IP로 접속하는 것도 그대로 된다.
 * - 그 밖의 이름은 optimeter.allowed-hosts에 적어둔 것만 통과시킨다 (예: my-mac.local).
 *
 * API뿐 아니라 화면 파일 요청에도 적용한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HostHeaderFilter.class);

    static final String REJECTED_MESSAGE = "이 주소로는 접속할 수 없습니다. http://localhost 또는 IP 주소로 접속하세요. "
            + "다른 이름으로 접속해야 한다면 서버를 실행할 때 --optimeter.allowed-hosts=<이름>을 지정하세요.";

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    // 16진수와 콜론으로 된 꼴 (끝이 IPv4인 ::ffff:127.0.0.1 포함). 도메인 이름에는 콜론과 대괄호를 쓸 수 없다.
    // 서블릿 컨테이너에 따라 대괄호를 붙여서 주기도 떼고 주기도 해서 둘 다 받는다.
    private static final Pattern IPV6 = Pattern.compile("\\[?[0-9a-f:.]*:[0-9a-f:.]*]?");
    private static final int LOGGED_HOST_MAX_CHARS = 100;

    private final Set<String> extraHosts;
    private final ObjectMapper objectMapper;

    /**
     * @param allowedHosts 추가로 허용할 호스트 이름들. 쉼표로 구분하고 포트는 적지 않는다 (비어 있으면 없음)
     */
    public HostHeaderFilter(@Value("${optimeter.allowed-hosts:}") String allowedHosts, ObjectMapper objectMapper) {
        this.extraHosts = Arrays.stream(allowedHosts.split(","))
                .map(HostHeaderFilter::normalize)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getServerName()은 Host 헤더에서 포트를 뗀 값이다. 브라우저는 항상 Host를 보낸다.
        // Host가 없는 HTTP/1.0 요청이면 서블릿 컨테이너가 서버 자신의 주소나 이름을 대신 돌려준다.
        String host = request.getServerName();
        if (isAllowed(host, extraHosts)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("허용되지 않은 Host로 온 요청을 거부했습니다: {} {} (Host: {})",
                request.getMethod(), request.getRequestURI(), forLog(host));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(REJECTED_MESSAGE));
    }

    /**
     * @param host       포트를 뗀 Host 값 (예: "localhost", "192.168.0.5", "[::1]")
     * @param extraHosts 추가로 허용할 이름들 (소문자)
     */
    static boolean isAllowed(String host, Set<String> extraHosts) {
        if (host == null) {
            return false;
        }
        String name = normalize(host);
        return name.equals("localhost") || isIpLiteral(name) || extraHosts.contains(name);
    }

    /** 호스트 이름은 대소문자를 가리지 않고, 끝에 점을 붙여도(localhost.) 같은 이름이다. */
    private static String normalize(String host) {
        String name = host.strip().toLowerCase(Locale.ROOT);
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static boolean isIpLiteral(String name) {
        Matcher ipv4 = IPV4.matcher(name);
        if (ipv4.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(ipv4.group(i)) > 255) {
                    return false;
                }
            }
            return true;
        }
        return IPV6.matcher(name).matches();
    }

    private static String forLog(String host) {
        if (host == null) {
            return "(없음)";
        }
        String printable = host.replaceAll("[\\r\\n\\t]", "?");
        return printable.length() > LOGGED_HOST_MAX_CHARS
                ? printable.substring(0, LOGGED_HOST_MAX_CHARS) + "..." : printable;
    }
}
