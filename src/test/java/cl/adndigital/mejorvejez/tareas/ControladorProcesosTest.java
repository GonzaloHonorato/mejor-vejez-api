package cl.adndigital.mejorvejez.tareas;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/** Contrato HTTP de {@link ControladorProcesos}; el servicio es un mock. */
@WebMvcTest(ControladorProcesos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorProcesosTest {

    private static final String URL = "/api/administracion/procesos";

    private static final RespuestaEjecucion EJECUCION = new RespuestaEjecucion(41L, "GENERACION_TAREAS",
            OffsetDateTime.parse("2026-11-02T00:00-03:00"), OffsetDateTime.parse("2026-11-09T00:00-03:00"), 88L,
            OffsetDateTime.parse("2026-11-02T00:05-03:00"), OffsetDateTime.parse("2026-11-02T00:06-03:00"), 14, 1,
            "Detalle 15: sin días u horas válidos");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioGeneracionTareas servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void generarTareasDevuelveLaEjecucion() throws Exception {
        when(servicio.generarManual()).thenReturn(EJECUCION);

        mockMvc.perform(post(URL + "/generacion-tareas").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(41))
                .andExpect(jsonPath("$.tipoEjecucion").value("GENERACION_TAREAS"))
                .andExpect(jsonPath("$.inicioVentana").value("2026-11-02T00:00:00-03:00"))
                .andExpect(jsonPath("$.finVentana").value("2026-11-09T00:00:00-03:00"))
                .andExpect(jsonPath("$.idEstado").value(88))
                .andExpect(jsonPath("$.fechaInicio").value("2026-11-02T00:05:00-03:00"))
                .andExpect(jsonPath("$.fechaFin").value("2026-11-02T00:06:00-03:00"))
                .andExpect(jsonPath("$.cantidadCreada").value(14))
                .andExpect(jsonPath("$.cantidadErrores").value(1))
                .andExpect(jsonPath("$.resumenErrores").value("Detalle 15: sin días u horas válidos"));
    }

    @Test
    void generarTareasSinSerAdministradorDevuelve403() throws Exception {
        when(servicio.generarManual())
                .thenThrow(new ExcepcionAccesoDenegado("Solo un administrador puede realizar esta acción"));

        mockMvc.perform(post(URL + "/generacion-tareas").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));
    }

    @Test
    void generarTareasSinAutenticarDevuelve401() throws Exception {
        mockMvc.perform(post(URL + "/generacion-tareas"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listarEjecucionesPorTipo() throws Exception {
        when(servicio.listarEjecuciones("GENERACION_TAREAS")).thenReturn(List.of(EJECUCION));

        mockMvc.perform(get(URL).param("tipo", "GENERACION_TAREAS").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idEjecucion").value(41))
                .andExpect(jsonPath("$[0].cantidadCreada").value(14));
        verify(servicio).listarEjecuciones("GENERACION_TAREAS");
    }

    @Test
    void listarEjecucionesSinSerAdministradorDevuelve403() throws Exception {
        when(servicio.listarEjecuciones("GENERACION_TAREAS"))
                .thenThrow(new ExcepcionAccesoDenegado("Solo un administrador puede realizar esta acción"));

        mockMvc.perform(get(URL).param("tipo", "GENERACION_TAREAS").with(jwt()))
                .andExpect(status().isForbidden());
    }
}
