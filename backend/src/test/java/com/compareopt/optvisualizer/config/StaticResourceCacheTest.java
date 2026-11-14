package com.compareopt.optvisualizer.config;

import com.compareopt.optvisualizer.controller.CompileController;
import com.compareopt.optvisualizer.service.CompilerRegistry;
import com.compareopt.optvisualizer.service.CompilerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * jar에 묶인 화면 파일의 캐시 헤더를 확인한다 (StaticResourceConfig, application.yml의 spring.web.resources.cache).
 * 화면 파일은 src/test/resources/static에 둔 테스트용 파일을 쓴다.
 */
@WebMvcTest(CompileController.class)
class StaticResourceCacheTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompilerService compilerService;

    @MockBean
    private CompilerRegistry compilerRegistry;

    @Test
    void 주소창의_루트는_index_html로_넘긴다() throws Exception {
        // MockMvc는 forward를 실제로 따라가지 않는다. 실제 서버에서는 넘겨받은 /index.html 처리가 아래 테스트의
        // 헤더를 붙인다 (jar를 띄워 curl로 확인하는 .github/scripts/smoke-test.sh에서 검증).
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void index_html은_브라우저가_매번_다시_받도록_내보낸다() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                // 시각으로 304를 주면 예전 버전 jar로 되돌렸을 때 다른 버전의 캐시가 쓰일 수 있어 일부러 뺀다
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED));
    }

    @Test
    void 해시가_붙은_assets_파일은_1년간_다시_묻지_않고_캐시한다() throws Exception {
        mockMvc.perform(get("/assets/index-Bx3k9Q2a.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, immutable"));
    }

    @Test
    void assets_아래_없는_파일은_404이고_캐시_헤더를_붙이지_않는다() throws Exception {
        mockMvc.perform(get("/assets/index-NotThere.js"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void API_응답에는_화면_파일의_캐시_설정이_붙지_않는다() throws Exception {
        when(compilerRegistry.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/compilers"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }
}
