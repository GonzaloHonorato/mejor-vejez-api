package cl.adndigital.mejorvejez.configuracion;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Verifica {@link FiltroClaveServicio} y la cadena interna de {@link
 * ConfiguracionSeguridad} ({@code @Order(1)}, {@code
 * securityMatcher("/api/interno/**")}) contra {@link ControladorEcoInterno},
 * un controlador de prueba en {@code /api/interno/eco} que solo existe para
 * esta clase. La cadena pública ({@code @Order(2)}) también está en el
 * contexto para probar que la clave de servicio nunca abre una ruta pública
 * protegida (se usa {@code /api/cualquier-cosa}, sin controlador, igual que
 * {@code ManejadorErroresSeguridadTest}: Spring Security responde 401 antes
 * de que el despachador busque una ruta).
 */
@WebMvcTest(controllers = ControladorEcoInterno.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErroresSeguridad.class})
@TestPropertySource(properties = "CLAVE_SERVICIO=" + FiltroClaveServicioTest.CLAVE_CORRECTA)
class FiltroClaveServicioTest {

    static final String CLAVE_CORRECTA = "clave-de-prueba-correcta";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rutaInternaSinClaveDa401() throws Exception {
        mockMvc.perform(get("/api/interno/eco"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"))
                .andExpect(jsonPath("$.detail").value("Clave de servicio inválida"));
    }

    @Test
    void rutaInternaClaveIncorrectaDa401() throws Exception {
        mockMvc.perform(get("/api/interno/eco")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, "clave-equivocada"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));
    }

    @Test
    void rutaInternaConJwtDa401() throws Exception {
        mockMvc.perform(get("/api/interno/eco")
                        .header("Authorization", "Bearer cualquier-jwt-de-usuario"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        // La cadena interna no configura oauth2ResourceServer: el JWT nunca se valida.
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void rutaPublicaConClaveDa401() throws Exception {
        mockMvc.perform(get("/api/cualquier-cosa")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"));
    }

    @Test
    void rutaInternaConClaveCorrectaPasa() throws Exception {
        mockMvc.perform(get("/api/interno/eco")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA))
                .andExpect(status().isOk())
                .andExpect(content().string("eco"));
    }

    @Nested
    @TestPropertySource(properties = "CLAVE_SERVICIO=")
    class SinVariableDeEntorno {

        @Test
        void sinVariableClaveTodasLasInternasDan401() throws Exception {
            mockMvc.perform(get("/api/interno/eco")
                            .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));
        }

        /**
         * El caso peligroso de {@code ClaveServicio#coincideCon}: con la clave
         * configurada vacía, una cabecera también vacía NO debe pasar (si
         * alguien quitara las guardas, comparar "" con "" daría {@code true}).
         */
        @Test
        void sinVariableClaveYCabeceraVaciaDa401() throws Exception {
            mockMvc.perform(get("/api/interno/eco")
                            .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, ""))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));
        }
    }
}
