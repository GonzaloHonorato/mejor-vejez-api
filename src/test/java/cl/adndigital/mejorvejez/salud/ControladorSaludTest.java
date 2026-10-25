package cl.adndigital.mejorvejez.salud;

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorSalud.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErroresSeguridad.class})
class ControladorSaludTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioSalud servicioSalud;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void saludEsPublicaYReportaLaBaseDeDatos() throws Exception {
        when(servicioSalud.verificar()).thenReturn(new RespuestaSalud(
                "DEGRADADO", "mejor-vejez-api", "test", Instant.now(),
                new RespuestaSalud.Componente("NO_DISPONIBLE", null)));

        mockMvc.perform(get("/api/salud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DEGRADADO"))
                .andExpect(jsonPath("$.baseDatos.estado").value("NO_DISPONIBLE"));
    }

    @Test
    void elRestoExigeToken() throws Exception {
        mockMvc.perform(get("/api/cualquier-cosa"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenInvalidoDevuelve401() throws Exception {
        when(jwtDecoder.decode("x")).thenThrow(new BadJwtException("token inválido"));

        mockMvc.perform(get("/api/cualquier-cosa").header("Authorization", "Bearer x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPermiteYExponeLaCabeceraDeCorrelacion() throws Exception {
        mockMvc.perform(options("/api/salud")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Id-Correlacion"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Id-Correlacion")));

        when(servicioSalud.verificar()).thenReturn(new RespuestaSalud(
                "DISPONIBLE", "mejor-vejez-api", "test", Instant.now(),
                new RespuestaSalud.Componente("DISPONIBLE", 1L)));
        mockMvc.perform(get("/api/salud").header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Id-Correlacion")));
    }
}
