package cl.adndigital.mejorvejez.alertas;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

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
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/** Contrato HTTP de {@link ControladorProcesosAlertas} (regla 12 de la Fase 5); el servicio es un mock. */
@WebMvcTest(ControladorProcesosAlertas.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorProcesosAlertasTest {

    private static final String URL = "/api/administracion/procesos/deteccion-atrasos";

    private static final RespuestaEjecucion EJECUCION = new RespuestaEjecucion(52L, "DETECCION_ATRASOS",
            OffsetDateTime.parse("2026-10-31T10:00-03:00"), OffsetDateTime.parse("2026-11-02T10:00-03:00"), 88L,
            OffsetDateTime.parse("2026-11-02T10:00-03:00"), OffsetDateTime.parse("2026-11-02T10:01-03:00"), 2, 1,
            "Alerta 7: sin destinatarios con canal operativo");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioDeteccionAtrasos servicio;

    @MockitoBean
    private ServicioAlertasPreventivas servicioAlertasPreventivas;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void deteccionAtrasosDevuelveLaEjecucionAlAdministrador() throws Exception {
        when(servicio.ejecutarManual()).thenReturn(EJECUCION);

        mockMvc.perform(post(URL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(52))
                .andExpect(jsonPath("$.tipoEjecucion").value("DETECCION_ATRASOS"))
                .andExpect(jsonPath("$.inicioVentana").value("2026-10-31T10:00:00-03:00"))
                .andExpect(jsonPath("$.finVentana").value("2026-11-02T10:00:00-03:00"))
                .andExpect(jsonPath("$.cantidadCreada").value(2))
                .andExpect(jsonPath("$.cantidadErrores").value(1))
                .andExpect(jsonPath("$.resumenErrores").value("Alerta 7: sin destinatarios con canal operativo"));
    }

    @Test
    void deteccionAtrasosSinSerAdministradorDevuelve403() throws Exception {
        when(servicio.ejecutarManual())
                .thenThrow(new ExcepcionAccesoDenegado("Solo un administrador puede realizar esta acción"));

        mockMvc.perform(post(URL).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));
    }

    @Test
    void deteccionAtrasosSinAutenticarDevuelve401() throws Exception {
        mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void alertasPreventivasDevuelveLaEjecucionAlAdministrador() throws Exception {
        RespuestaEjecucion ejecucion = new RespuestaEjecucion(53L, "ALERTAS_PREVENTIVAS",
                OffsetDateTime.parse("2026-11-02T10:00-03:00"), OffsetDateTime.parse("2026-11-03T10:00-03:00"), 88L,
                OffsetDateTime.parse("2026-11-02T10:00-03:00"), OffsetDateTime.parse("2026-11-02T10:01-03:00"), 1, 0,
                null);
        when(servicioAlertasPreventivas.ejecutarManual()).thenReturn(ejecucion);

        mockMvc.perform(post("/api/administracion/procesos/alertas-preventivas").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(53))
                .andExpect(jsonPath("$.tipoEjecucion").value("ALERTAS_PREVENTIVAS"))
                .andExpect(jsonPath("$.cantidadCreada").value(1))
                .andExpect(jsonPath("$.cantidadErrores").value(0));
    }

    @Test
    void alertasPreventivasSinSerAdministradorDevuelve403() throws Exception {
        when(servicioAlertasPreventivas.ejecutarManual())
                .thenThrow(new ExcepcionAccesoDenegado("Solo un administrador puede realizar esta acción"));

        mockMvc.perform(post("/api/administracion/procesos/alertas-preventivas").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));
    }
}
