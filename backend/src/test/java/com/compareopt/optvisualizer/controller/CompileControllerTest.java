package com.compareopt.optvisualizer.controller;

import com.compareopt.optvisualizer.dto.OptimizationResult;
import com.compareopt.optvisualizer.service.CompilerRegistry;
import com.compareopt.optvisualizer.service.CompilerService;
import com.compareopt.optvisualizer.toolchain.Language;
import com.compareopt.optvisualizer.toolchain.OptLevel;
import com.compareopt.optvisualizer.toolchain.OptRemark;
import com.compareopt.optvisualizer.toolchain.ToolchainKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 계층만 검증한다 (실제 clang 호출은 CompilerService를 mock으로 대체).
 * 실제 컴파일 동작은 CompilerServiceTest에서 검증함.
 */
@WebMvcTest(CompileController.class)
class CompileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompilerService compilerService;

    @MockBean
    private CompilerRegistry compilerRegistry;

    @Test
    void 컴파일러_목록에는_언어가_함께_담기고_실행_파일_경로는_나가지_않는다() throws Exception {
        when(compilerRegistry.listAll()).thenReturn(List.of(new CompilerRegistry.ResolvedCompiler(
                "gxx", "GNU G++", Language.CPP, ToolchainKind.GCC.toolchain(), "/opt/gcc/bin/g++", true)));

        mockMvc.perform(get("/api/compilers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("gxx"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].language").value("cpp"))
                .andExpect(jsonPath("$[0].languageLabel").value("C++"))
                .andExpect(jsonPath("$[0].path").doesNotExist());
    }

    @Test
    void 정상적인_요청은_200과_결과_리스트를_반환한다() throws Exception {
        OptLevel o0 = new OptLevel("O0", "-O0", "최적화 없음");
        when(compilerService.compareOptimizationLevels(anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of(OptimizationResult.success(o0, "ret", 100, 10)));

        mockMvc.perform(post("/api/compile")
                        .contentType("application/json")
                        .content("{\"code\":\"int f(){return 0;}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].level").value("O0"))
                .andExpect(jsonPath("$[0].levelLabel").value("-O0"))
                .andExpect(jsonPath("$[0].levelDescription").value("최적화 없음"))
                .andExpect(jsonPath("$[0].success").value(true))
                // remark를 내지 않는 컴파일러의 결과는 remarks가 null이다
                .andExpect(jsonPath("$[0].remarks").value(nullValue()));
    }

    @Test
    void 최적화_remark는_화면이_그대로_그릴_수_있는_형태로_내려간다() throws Exception {
        OptimizationResult result = OptimizationResult.success(new OptLevel("O2", "-O2", "권장 수준"), "ret", 100, 10);
        result.setRemarks(List.of(new OptRemark(OptRemark.Kind.APPLIED, 5, 18, "inline", "함수 인라이닝",
                "함수 호출을 없앱니다.", "'square' inlined into 'sum_loop'", 2)), 3);
        when(compilerService.compareOptimizationLevels(anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of(result));

        mockMvc.perform(post("/api/compile")
                        .contentType("application/json")
                        .content("{\"code\":\"int f(){return 0;}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remarks[0].kind").value("APPLIED"))
                .andExpect(jsonPath("$[0].remarks[0].line").value(5))
                .andExpect(jsonPath("$[0].remarks[0].column").value(18))
                .andExpect(jsonPath("$[0].remarks[0].pass").value("inline"))
                .andExpect(jsonPath("$[0].remarks[0].topicLabel").value("함수 인라이닝"))
                .andExpect(jsonPath("$[0].remarks[0].topicDescription").value("함수 호출을 없앱니다."))
                .andExpect(jsonPath("$[0].remarks[0].message").value("'square' inlined into 'sum_loop'"))
                .andExpect(jsonPath("$[0].remarks[0].count").value(2))
                .andExpect(jsonPath("$[0].omittedRemarkCount").value(3));
    }

    @Test
    void 코드가_비어있으면_400과_친절한_에러메시지를_반환한다() throws Exception {
        mockMvc.perform(post("/api/compile")
                        .contentType("application/json")
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("코드를 입력해주세요."));
    }

    @Test
    void code_필드가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/compile")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 다른_웹사이트에서_보내는_요청은_사전_요청_단계에서_거부된다() throws Exception {
        // 이 API는 받은 코드를 컴파일/실행하므로, 사용자가 방문한 다른 사이트의 스크립트가 호출할 수 없어야 한다.
        // 브라우저는 JSON POST 전에 사전 요청(OPTIONS)을 보내는데, 여기서 허용 헤더를 주지 않으면 본 요청을 보내지 않는다.
        mockMvc.perform(options("/api/compile")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 낯선_이름으로_온_요청은_컴파일하지_않고_403으로_거부한다() throws Exception {
        // DNS 리바인딩: 다른 사이트가 자기 도메인을 127.0.0.1로 바꿔치기하면 브라우저는 "같은 출처"로 보고
        // 사전 요청 없이 그대로 보낸다. 이때도 Host 헤더는 그 사이트의 이름이라 HostHeaderFilter가 걸러낸다.
        mockMvc.perform(post("/api/compile")
                        .header("Host", "evil.example:8080")
                        .header("Origin", "http://evil.example:8080")
                        .contentType("application/json")
                        .content("{\"code\":\"int main() { return 0; }\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(compilerService);
    }

    @Test
    void IP_주소로_온_요청은_그대로_처리한다() throws Exception {
        when(compilerRegistry.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/compilers").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void 없는_주소는_500이_아니라_404를_반환한다() throws Exception {
        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }
}
