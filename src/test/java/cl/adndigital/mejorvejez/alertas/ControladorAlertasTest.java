package cl.adndigital.mejorvejez.alertas;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorAlertas} y {@link
 * ControladorCierreAlertas} (TSA-47, TSA-9). Los servicios son mocks: estas
 * pruebas no tocan la base de datos, solo el enlace de parámetros/JSON y la
 * traducción de excepciones (igual que {@code tareas.ControladorTareasTest}).
 */
@WebMvcTest({ControladorAlertas.class, ControladorCierreAlertas.class})
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorAlertasTest {

    private static final String URL = "/api/grupos/1/alertas";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioConsultaAlertas servicioConsulta;

    @MockitoBean
    private ServicioCierreAlertas servicioCierre;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Bandeja (GET)
    // ------------------------------------------------------------------

    @Test
    void listarPasaLosFiltrosAlServicio() throws Exception {
        when(servicioConsulta.listar(1L, 2L, CodigosAlertas.TIPO_ATRASO_TAREA, 9L, 0, 20))
                .thenReturn(new RespuestaPagina<>(List.of(), 0, 20, 0));

        mockMvc.perform(get(URL).with(jwt())
                        .param("idEstado", "2")
                        .param("tipoAlerta", CodigosAlertas.TIPO_ATRASO_TAREA)
                        .param("idAdulto", "9"))
                .andExpect(status().isOk());

        verify(servicioConsulta).listar(1L, 2L, CodigosAlertas.TIPO_ATRASO_TAREA, 9L, 0, 20);
    }

    @Test
    void tamanoSuperiorA100SeLimitaA100() throws Exception {
        when(servicioConsulta.listar(1L, null, null, null, 0, 100))
                .thenReturn(new RespuestaPagina<>(List.of(), 0, 100, 0));

        mockMvc.perform(get(URL).with(jwt()).param("tamano", "500"))
                .andExpect(status().isOk());

        verify(servicioConsulta).listar(1L, null, null, null, 0, 100);
    }

    @Test
    void paginaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get(URL).with(jwt()).param("pagina", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicioConsulta);
    }

    @Test
    void listarDevuelveLaPaginaConOffsetDeSantiago() throws Exception {
        when(servicioConsulta.listar(eq(1L), eq((Long) null), eq((String) null), eq((Long) null), eq(0), eq(20)))
                .thenReturn(new RespuestaPagina<>(List.of(respuesta(5L)), 0, 20, 1));

        mockMvc.perform(get(URL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].idAlerta").value(5))
                .andExpect(jsonPath("$.elementos[0].fechaDeteccion").value("2026-11-02T08:00:00-03:00"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicioConsulta);
    }

    // ------------------------------------------------------------------
    // Detalle (GET /{idAlerta})
    // ------------------------------------------------------------------

    @Test
    void obtenerDevuelve200() throws Exception {
        RespuestaAlertaDetallada detalle = new RespuestaAlertaDetallada(respuesta(5L), List.of());
        when(servicioConsulta.obtener(1L, 5L)).thenReturn(detalle);

        mockMvc.perform(get(URL + "/5").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerta.idAlerta").value(5))
                .andExpect(jsonPath("$.alerta.fechaDeteccion").value("2026-11-02T08:00:00-03:00"))
                .andExpect(jsonPath("$.intentos").isArray());
    }

    @Test
    void obtenerDeAlertaInexistenteDevuelve404() throws Exception {
        when(servicioConsulta.obtener(1L, 99L)).thenThrow(new ExcepcionNoEncontrado("La alerta no existe"));

        mockMvc.perform(get(URL + "/99").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La alerta no existe"));
    }

    @Test
    void obtenerExigeToken() throws Exception {
        mockMvc.perform(get(URL + "/5")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicioConsulta);
    }

    // ------------------------------------------------------------------
    // Cierre (POST /{idAlerta}/cierre)
    // ------------------------------------------------------------------

    @Test
    void cerrarDevuelve200() throws Exception {
        RespuestaAlertaDetallada detalle = new RespuestaAlertaDetallada(respuesta(5L), List.of());
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta("Se habló con la familia")))
                .thenReturn(detalle);

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacion\":\"Se habló con la familia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerta.idAlerta").value(5));
    }

    @Test
    void cerrarSinPermisoDevuelve403() throws Exception {
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta("Observación")))
                .thenThrow(new ExcepcionAccesoDenegado("Solo un coordinador puede realizar esta acción"));

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacion\":\"Observación\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un coordinador puede realizar esta acción"));
    }

    @Test
    void cerrarSinObservacionDevuelve422() throws Exception {
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta(null)))
                .thenThrow(new ExcepcionReglaNegocio("La observación es obligatoria para cerrar la alerta."));

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("La observación es obligatoria para cerrar la alerta."));
    }

    @Test
    void cerrarSinCuerpoSiendoColaboradorDevuelve403() throws Exception {
        // Sin cuerpo equivale a observación nula: el permiso se revisa primero (403, nunca 400).
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta(null)))
                .thenThrow(new ExcepcionAccesoDenegado("Solo un coordinador puede realizar esta acción"));

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un coordinador puede realizar esta acción"));
    }

    @Test
    void cerrarSinCuerpoSiendoCoordinadorDevuelve422() throws Exception {
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta(null)))
                .thenThrow(new ExcepcionReglaNegocio("La observación es obligatoria para cerrar la alerta."));

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("La observación es obligatoria para cerrar la alerta."));
        // Un `null` JSON literal también equivale a una observación nula (nunca 500).
        mockMvc.perform(post(URL + "/5/cierre").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cerrarAlertaYaCerradaDevuelve409() throws Exception {
        when(servicioCierre.cerrar(1L, 5L, new SolicitudCerrarAlerta("Observación")))
                .thenThrow(new ExcepcionConflicto("La alerta ya está cerrada."));

        mockMvc.perform(post(URL + "/5/cierre").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacion\":\"Observación\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La alerta ya está cerrada."));
    }

    @Test
    void cerrarExigeToken() throws Exception {
        mockMvc.perform(post(URL + "/5/cierre").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacion\":\"Observación\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servicioCierre);
    }

    private RespuestaAlerta respuesta(long idAlerta) {
        return new RespuestaAlerta(idAlerta, CodigosAlertas.TIPO_ATRASO_TAREA, 7L, "Confirmar medicamento", 1L, null,
                null, 3L, "Adulto Ejemplo", OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), "Mensaje de alerta",
                1L, OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), null, null, null);
    }
}
