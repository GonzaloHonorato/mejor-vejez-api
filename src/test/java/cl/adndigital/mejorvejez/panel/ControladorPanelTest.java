package cl.adndigital.mejorvejez.panel;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
 * Contrato HTTP de {@link ControladorPanel}. El servicio es un mock: estas
 * pruebas no tocan la base de datos, solo el enlace de parámetros/JSON
 * (igual que {@code tareas.ControladorTareasTest}).
 */
@WebMvcTest(ControladorPanel.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorPanelTest {

    private static final String URL = "/api/grupos/1/panel";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioPanel servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void tareasPasaIdAdultoYDevuelve200() throws Exception {
        RespuestaPanelTareas respuesta = new RespuestaPanelTareas(LocalDate.of(2026, 11, 2), 1, 0, 0, 0, 0, 0,
                List.of());
        when(servicio.tareas(1L, 9L)).thenReturn(respuesta);

        mockMvc.perform(get(URL + "/tareas").with(jwt()).param("idAdulto", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fecha").value("2026-11-02"))
                .andExpect(jsonPath("$.pendientes").value(1))
                .andExpect(jsonPath("$.tareasAtrasadas").isArray());
    }

    @Test
    void tareasSinIdAdultoDevuelve200() throws Exception {
        when(servicio.tareas(eq(1L), isNull()))
                .thenReturn(new RespuestaPanelTareas(LocalDate.of(2026, 11, 2), 0, 0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(get(URL + "/tareas").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void proximosHorariosDevuelve200() throws Exception {
        when(servicio.proximosHorarios(eq(1L), isNull())).thenReturn(new RespuestaPanelHorarios(List.of()));

        mockMvc.perform(get(URL + "/proximos-horarios").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tareas").isArray());
    }

    @Test
    void citasDevuelve200() throws Exception {
        when(servicio.citas(eq(1L), isNull())).thenReturn(new RespuestaPanelCitas(List.of()));

        mockMvc.perform(get(URL + "/citas").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citas").isArray());
    }

    @Test
    void alertasDevuelve200() throws Exception {
        when(servicio.alertas(eq(1L), isNull())).thenReturn(new RespuestaPanelAlertas(0, 0, List.of()));

        mockMvc.perform(get(URL + "/alertas").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abiertas").value(0))
                .andExpect(jsonPath("$.cerradasUltimos7Dias").value(0))
                .andExpect(jsonPath("$.recientes").isArray());
    }

    @Test
    void alertasPreventivasDevuelve200() throws Exception {
        when(servicio.alertasPreventivas(eq(1L), isNull())).thenReturn(new RespuestaPanelPreventivas(List.of()));

        mockMvc.perform(get(URL + "/alertas-preventivas").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertas").isArray());
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        mockMvc.perform(get(URL + "/tareas")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/proximos-horarios")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/citas")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/alertas")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/alertas-preventivas")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }
}
