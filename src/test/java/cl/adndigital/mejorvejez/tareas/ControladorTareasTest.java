package cl.adndigital.mejorvejez.tareas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorTareas}. El servicio es un mock: estas
 * pruebas no tocan la base de datos, solo el enlace de parámetros/JSON (igual
 * que {@code planificacion.ControladorDetallesPlanTest}).
 */
@WebMvcTest(ControladorTareas.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorTareasTest {

    private static final String URL = "/api/grupos/1/tareas";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioConsultaTareas servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void fechaMalformadaDevuelve400() throws Exception {
        mockMvc.perform(get(URL).with(jwt()).param("desde", "02-11-2026"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void fechaHastaMalformadaDevuelve400() throws Exception {
        mockMvc.perform(get(URL).with(jwt()).param("hasta", "no-es-una-fecha"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarPasaLosFiltrosAlServicio() throws Exception {
        when(servicio.listar(eq(1L), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).with(jwt())
                        .param("desde", "2026-11-02")
                        .param("hasta", "2026-11-03")
                        .param("idAdulto", "9")
                        .param("idEstado", "2")
                        .param("soloMias", "true"))
                .andExpect(status().isOk());

        verify(servicio).listar(1L,
                new FiltroTareas(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), 9L, 2L, true));
    }

    @Test
    void listarDevuelveFechaHorarioConOffset() throws Exception {
        when(servicio.listar(eq(1L), any())).thenReturn(List.of(respuesta(5L)));

        mockMvc.perform(get(URL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idTarea").value(5))
                .andExpect(jsonPath("$[0].fechaHorario").value("2026-11-02T08:00:00-03:00"))
                .andExpect(jsonPath("$[0].atrasada").value(false))
                .andExpect(jsonPath("$[0].compra").isArray())
                .andExpect(jsonPath("$[0].cita").doesNotExist());
    }

    @Test
    void obtenerDevuelve200() throws Exception {
        RespuestaTareaDetallada detalle = new RespuestaTareaDetallada(respuesta(5L), List.of(), List.of());
        when(servicio.obtener(1L, 5L)).thenReturn(detalle);

        mockMvc.perform(get(URL + "/5").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tarea.idTarea").value(5))
                .andExpect(jsonPath("$.tarea.fechaHorario").value("2026-11-02T08:00:00-03:00"))
                .andExpect(jsonPath("$.historial").isArray())
                .andExpect(jsonPath("$.asignaciones").isArray());
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    private RespuestaTarea respuesta(long idTarea) {
        return new RespuestaTarea(idTarea, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, 7L, "Adulto Ejemplo",
                null, "Control médico", "Control de rutina", OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), 30,
                1L, false, null, null, null, null, null, List.of(), null);
    }
}
