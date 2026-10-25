package cl.adndigital.mejorvejez.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.salud.ControladorSalud;
import cl.adndigital.mejorvejez.salud.ServicioSalud;

/**
 * Verifica que los rechazos que levanta Spring Security ANTES de llegar al
 * controlador (sin token, token inválido) salgan como {@link
 * org.springframework.http.ProblemDetail} en el mismo formato que {@code
 * ManejadorErrores}, no con el cuerpo por defecto de Spring Security.
 */
@WebMvcTest(ControladorSalud.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErroresSeguridad.class})
class ManejadorErroresSeguridadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ManejadorErroresSeguridad manejadorErroresSeguridad;

    @MockitoBean
    private ServicioSalud servicioSalud;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinTokenDevuelve401ComoProblemDetail() throws Exception {
        mockMvc.perform(get("/api/cualquier-cosa"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.detail").value("Se requiere una sesión válida."));
    }

    @Test
    void tokenInvalidoDevuelve401ComoProblemDetail() throws Exception {
        when(jwtDecoder.decode("x")).thenThrow(new BadJwtException("token inválido"));

        mockMvc.perform(get("/api/cualquier-cosa").header("Authorization", "Bearer x"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(content().string(not(containsString("token inválido"))));
    }

    @Test
    void accesoDenegadoDevuelve403ComoProblemDetail() throws Exception {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        manejadorErroresSeguridad.handle(solicitud, respuesta, new AccessDeniedException("detalle interno"));

        assertThat(respuesta.getStatus()).isEqualTo(403);
        assertThat(MediaType.parseMediaType(respuesta.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();

        String cuerpo = respuesta.getContentAsString();
        assertThat(cuerpo).doesNotContain("detalle interno");

        DocumentContext json = JsonPath.parse(cuerpo);
        assertThat(json.<String>read("$.title")).isEqualTo("Acceso denegado");
        assertThat(json.<String>read("$.detail")).isEqualTo("No tienes permiso para realizar esta acción.");
    }
}
