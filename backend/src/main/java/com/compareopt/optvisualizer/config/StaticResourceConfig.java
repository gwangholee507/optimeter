package com.compareopt.optvisualizer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * jar에 묶인 화면 파일 중 이름에 내용 해시가 붙은 것({@code /assets/index-<hash>.js} 등)은
 * 브라우저가 1년 동안 다시 묻지 않고 캐시하게 한다.
 *
 * Vite는 빌드할 때 JS/CSS와 src에서 import한 파일을 assets/ 아래에 내용 해시를 붙인 이름으로 내보낸다.
 * 내용이 바뀌면 이름도 바뀌므로 같은 주소의 파일은 언제나 같은 내용이고, 새 버전의 index.html은 새 이름을 가리킨다.
 *
 * 나머지 화면 파일(index.html, frontend/public에서 온 favicon.svg 등)은 버전이 바뀌어도 이름이 같아서
 * 오래 캐시하면 안 된다. 이것들은 Spring Boot의 기본 정적 리소스 핸들러가 내보내고, 캐시 설정(매번 다시 받기)은
 * application.yml의 spring.web.resources.cache에 있다.
 *
 * /api 응답은 컨트롤러가 내보내므로 이 설정과 상관없다.
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // "assets"는 Vite의 build.assetsDir 기본값이다 (frontend/vite.config.js에서 바꾸면 여기도 맞춰야 함).
        // Spring Boot의 기본 핸들러(/**)보다 구체적인 패턴이라 /assets/ 아래 요청은 이 핸들러가 받는다.
        // 없는 파일이면 404로 끝나고 이 캐시 헤더는 붙지 않는다 (404가 브라우저에 1년간 남지 않음).
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
    }
}
