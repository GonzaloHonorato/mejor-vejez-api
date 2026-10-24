package cl.adndigital.mejorvejez.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.config.CorsConfig;
import cl.adndigital.mejorvejez.config.SecurityConfig;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void healthEsPublicoYReportaLaBaseDeDatos() throws Exception {
        when(healthService.check()).thenReturn(new HealthResponse(
                "DEGRADED", "mejor-vejez-api", "test", Instant.now(),
                new HealthResponse.Component("DOWN", null)));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.database.status").value("DOWN"));
    }

    @Test
    void elRestoExigeToken() throws Exception {
        mockMvc.perform(get("/api/cualquier-cosa"))
                .andExpect(status().isUnauthorized());
    }
}
