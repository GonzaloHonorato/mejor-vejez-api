package cl.adndigital.mejorvejez.notificaciones;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Prueba {@link ControladorCanales} con {@code MockMvc} (mismo patrón que
 * {@code usuarios.ControladorYoTest}): {@link ServicioCanales} se reemplaza
 * con un mock, porque resuelve el usuario autenticado internamente.
 */
@WebMvcTest(ControladorCanales.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorCanalesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioCanales servicioCanales;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getDevuelveMisCanales() throws Exception {
        when(servicioCanales.misCanales()).thenReturn(new RespuestaCanales(
                List.of(new RespuestaCanal(CodigosNotificaciones.CANAL_CORREO, "ana@correo.cl", true, true)), false));

        mockMvc.perform(get("/api/yo/canales").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canales[0].canal").value(CodigosNotificaciones.CANAL_CORREO))
                .andExpect(jsonPath("$.canales[0].predeterminado").value(true))
                .andExpect(jsonPath("$.whatsappDisponible").value(false));
    }

    @Test
    void putActualizaMisCanales() throws Exception {
        when(servicioCanales.actualizarMisCanales(any())).thenReturn(new RespuestaCanales(
                List.of(new RespuestaCanal(CodigosNotificaciones.CANAL_CORREO, "ana@correo.cl", true, false)), false));

        mockMvc.perform(put("/api/yo/canales")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canales\":[{\"canal\":\"CORREO\",\"destino\":\"ana@correo.cl\",\"habilitado\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canales[0].canal").value(CodigosNotificaciones.CANAL_CORREO))
                .andExpect(jsonPath("$.canales[0].predeterminado").value(false));
    }

    @Test
    void getSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/yo/canales"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putSinTokenDevuelve401() throws Exception {
        mockMvc.perform(put("/api/yo/canales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canales\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
