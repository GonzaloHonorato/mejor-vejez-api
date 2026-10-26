package cl.adndigital.mejorvejez.tareas;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorEstadoTareas}. El servicio es un mock:
 * estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON y la traducción de excepciones (igual que {@code
 * ControladorTareasTest}).
 */
@WebMvcTest(ControladorEstadoTareas.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorEstadoTareasTest {

    private static final String URL = "/api/grupos/1/tareas/5/estado";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioEstadoTareas servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinIdEstadoDevuelve400() throws Exception {
        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idEstado"));
    }

    @Test
    void reglaDeNegocioDevuelve422ConDetail() throws Exception {
        when(servicio.cambiarEstado(eq(1L), eq(5L), eq(new SolicitudCambiarEstadoTarea(3L, null))))
                .thenThrow(new ExcepcionReglaNegocio("Indica una observación"));

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idEstado\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Indica una observación"));
    }

    @Test
    void cambiarEstadoDevuelve200() throws Exception {
        RespuestaTarea respuesta = respuesta(5L);
        when(servicio.cambiarEstado(eq(1L), eq(5L), eq(new SolicitudCambiarEstadoTarea(2L, "Listo"))))
                .thenReturn(respuesta);

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idEstado\":2,\"observacion\":\"Listo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idTarea").value(5))
                .andExpect(jsonPath("$.idEstado").value(2));
    }

    @Test
    void cambiarEstadoExigeToken() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"idEstado\":2}"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaTarea respuesta(long idTarea) {
        return new RespuestaTarea(idTarea, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, 7L, "Adulto Ejemplo",
                null, "Control médico", "Control de rutina", OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), 30,
                2L, false, null, null, null, null, null, List.of(), null);
    }
}
