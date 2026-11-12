package com.compareopt.optvisualizer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HostHeaderFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "LOCALHOST", "localhost.",
            "127.0.0.1", "192.168.0.5", "0.0.0.0",
            "[::1]", "::1", "[fe80::1c2a:3bff:fe4d:5e6f]", "[::ffff:127.0.0.1]"})
    void localhost와_IP_주소는_통과한다(String host) {
        assertThat(HostHeaderFilter.isAllowed(host, Set.of())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "evil.example",
            // 허용되는 이름을 앞뒤에 붙여서 속이려는 경우
            "localhost.evil.example", "127.0.0.1.evil.example", "evil-localhost", "evil.example.localhost",
            // IP 주소처럼 보이지만 아닌 것
            "999.1.1.1", "127.0.0.1.5", "127.1", "1234",
            "", " "})
    void 그_밖의_이름은_거부한다(String host) {
        assertThat(HostHeaderFilter.isAllowed(host, Set.of())).isFalse();
    }

    @Test
    void Host가_없으면_거부한다() {
        assertThat(HostHeaderFilter.isAllowed(null, Set.of())).isFalse();
    }

    @Test
    void 설정에_적은_이름은_대소문자와_공백을_가리지_않고_통과한다() throws Exception {
        HostHeaderFilter filter = new HostHeaderFilter(" My-Mac.local , lab-pc ,", new ObjectMapper());

        assertThat(statusFor(filter, "my-mac.local:8080")).isEqualTo(200);
        assertThat(statusFor(filter, "LAB-PC")).isEqualTo(200);
        assertThat(statusFor(filter, "other-pc.local")).isEqualTo(403);
    }

    @Test
    void 설정이_비어_있으면_빈_이름을_허용하지_않는다() throws Exception {
        HostHeaderFilter filter = new HostHeaderFilter("", new ObjectMapper());

        assertThat(statusFor(filter, "localhost:8080")).isEqualTo(200);
        assertThat(statusFor(filter, "evil.example:8080")).isEqualTo(403);
    }

    @Test
    void 거부하면_다음_단계로_넘기지_않고_message가_담긴_JSON으로_응답한다() throws Exception {
        HostHeaderFilter filter = new HostHeaderFilter("", new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/compile");
        request.addHeader("Host", "evil.example:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("컨트롤러까지 가면 안 된다").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                .isEqualTo(HostHeaderFilter.REJECTED_MESSAGE);
    }

    private static int statusFor(HostHeaderFilter filter, String hostHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compilers");
        request.addHeader("Host", hostHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
