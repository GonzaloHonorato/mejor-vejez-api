package cl.adndigital.mejorvejez.tareas;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Contrato HTTP de {@link ControladorAsignacionTareas}. El servicio es un
 * mock: estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON y la traducción de excepciones (igual que {@code
 * ControladorEstadoTareasTest}).
 */
@WebMvcTest(ControladorAsignacionTareas.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorAsignacionTareasTest {

    private static final String URL = "/api/grupos/1/tareas/5/asignacion";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioAsignacionTareas servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinIdUsuarioDevuelve400() throws Exception {
        mockMvc.perform(put(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idUsuario"));
    }

    @Test
    void reglaDeNegocioDevuelve422ConDetail() throws Exception {
        when(servicio.asignar(eq(1L), eq(5L), eq(new SolicitudAsignarTarea(9L, null))))
                .thenThrow(new ExcepcionReglaNegocio("El responsable debe ser integrante activo del grupo"));

        mockMvc.perform(put(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuario\":9}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("El responsable debe ser integrante activo del grupo"));
    }

    @Test
    void asignarDevuelve200() throws Exception {
        RespuestaTarea respuesta = respuesta(5L, 9L);
        when(servicio.asignar(eq(1L), eq(5L), eq(new SolicitudAsignarTarea(9L, "Turno de la tarde"))))
                .thenReturn(respuesta);

        mockMvc.perform(put(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuario\":9,\"motivo\":\"Turno de la tarde\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idTarea").value(5))
                .andExpect(jsonPath("$.responsable.idUsuario").value(9));
    }

    @Test
    void asignarExigeToken() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"idUsuario\":9}"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaTarea respuesta(long idTarea, long idResponsable) {
        return new RespuestaTarea(idTarea, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, 7L, "Adulto Ejemplo",
                null, "Control médico", "Control de rutina", OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), 30,
                1L, false, new RespuestaResponsable(idResponsable, "Responsable Ejemplo"), null, null, null, null,
                List.of(), null);
    }
}
