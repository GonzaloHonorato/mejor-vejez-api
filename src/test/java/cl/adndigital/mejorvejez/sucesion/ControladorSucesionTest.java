package cl.adndigital.mejorvejez.sucesion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorSucesion} (HU-13, TSA-53, regla 15 de
 * la Fase 7). El servicio es un mock: estas pruebas no tocan la base de
 * datos, solo las rutas, el enlace de parámetros/JSON, el 204 de {@code
 * /activa}, la traducción de excepciones y la validación del cuerpo, igual
 * que {@code relevos.ControladorRelevosTest}.
 */
@WebMvcTest(ControladorSucesion.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorSucesionTest {

    private static final String URL_BASE = "/api/grupos/1/sucesion";

    private static final RespuestaPropuesta PROPUESTA_DE_EJEMPLO = new RespuestaPropuesta(10L, 1L,
            new RespuestaPersona(3L, "Saliente"), new RespuestaPersona(4L, "Candidato"), 1L, "PENDIENTE",
            OffsetDateTime.now(), null, null, null, null, List.of());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioSucesion servicio;

    @MockitoBean
    private ServicioCompletarSucesion servicioCompletar;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Proponer (POST)
    // ------------------------------------------------------------------

    @Test
    void proponerDevuelve201ConLocation() throws Exception {
        when(servicio.proponer(1L, 4L)).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioCandidato\":4}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/1/sucesion/10"))
                .andExpect(jsonPath("$.idPropuesta").value(10))
                .andExpect(jsonPath("$.candidato.idUsuario").value(4))
                .andExpect(jsonPath("$.codigoEstado").value("PENDIENTE"))
                .andExpect(jsonPath("$.condicionesPendientes").isArray());

        verify(servicio).proponer(1L, 4L);
    }

    @Test
    void proponerSinCandidatoDevuelve400() throws Exception {
        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioCandidato\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void proponerSinTokenDevuelve401() throws Exception {
        mockMvc.perform(post(URL_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioCandidato\":4}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void proponerConPropuestaActivaDevuelve409() throws Exception {
        when(servicio.proponer(1L, 4L))
                .thenThrow(new ExcepcionConflicto(ServicioSucesion.MENSAJE_YA_EXISTE_ACTIVA));

        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioCandidato\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(ServicioSucesion.MENSAJE_YA_EXISTE_ACTIVA));
    }

    @Test
    void proponerSinSerCoordinadorDevuelve403() throws Exception {
        when(servicio.proponer(1L, 4L))
                .thenThrow(new ExcepcionAccesoDenegado(ServicioSucesion.MENSAJE_SOLO_COORDINADOR_PROPONE));

        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioCandidato\":4}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Consultas (GET)
    // ------------------------------------------------------------------

    @Test
    void historialDevuelveLista() throws Exception {
        when(servicio.historial(1L)).thenReturn(List.of(PROPUESTA_DE_EJEMPLO));

        mockMvc.perform(get(URL_BASE).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idPropuesta").value(10));

        verify(servicio).historial(1L);
    }

    @Test
    void activaDevuelve200ConPropuesta() throws Exception {
        when(servicio.activa(1L)).thenReturn(Optional.of(PROPUESTA_DE_EJEMPLO));

        mockMvc.perform(get(URL_BASE + "/activa").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPropuesta").value(10));
    }

    @Test
    void activaDevuelve204SinPropuesta() throws Exception {
        when(servicio.activa(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get(URL_BASE + "/activa").with(jwt()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void obtenerPasaLosIdsAlServicio() throws Exception {
        when(servicio.obtener(1L, 10L)).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(get(URL_BASE + "/10").with(jwt())).andExpect(status().isOk());

        verify(servicio).obtener(1L, 10L);
    }

    @Test
    void obtenerDeOtroGrupoDevuelve404() throws Exception {
        when(servicio.obtener(1L, 10L))
                .thenThrow(new ExcepcionNoEncontrado(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE));

        mockMvc.perform(get(URL_BASE + "/10").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE));
    }

    @Test
    void consultasSinTokenDevuelven401() throws Exception {
        mockMvc.perform(get(URL_BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL_BASE + "/activa")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL_BASE + "/10")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------
    // Aceptar / rechazar / cancelar (POST)
    // ------------------------------------------------------------------

    @Test
    void aceptarYRechazarLlamanAlServicio() throws Exception {
        when(servicio.aceptar(1L, 10L)).thenReturn(PROPUESTA_DE_EJEMPLO);
        when(servicio.rechazar(1L, 10L)).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/aceptar").with(jwt())).andExpect(status().isOk());
        mockMvc.perform(post(URL_BASE + "/10/rechazar").with(jwt())).andExpect(status().isOk());

        verify(servicio).aceptar(1L, 10L);
        verify(servicio).rechazar(1L, 10L);
    }

    @Test
    void cancelarConMotivoLoPasaAlServicio() throws Exception {
        when(servicio.cancelar(1L, 10L, "Cambio de planes")).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/cancelar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Cambio de planes\"}"))
                .andExpect(status().isOk());

        verify(servicio).cancelar(1L, 10L, "Cambio de planes");
    }

    @Test
    void cancelarSinCuerpoPasaMotivoNulo() throws Exception {
        when(servicio.cancelar(1L, 10L, null)).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/cancelar").with(jwt())).andExpect(status().isOk());

        verify(servicio).cancelar(1L, 10L, null);
    }

    @Test
    void cancelarConMotivoDeMasDe500CaracteresDevuelve400() throws Exception {
        mockMvc.perform(post(URL_BASE + "/10/cancelar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void transicionInvalidaDevuelve409() throws Exception {
        when(servicio.aceptar(1L, 10L))
                .thenThrow(new ExcepcionConflicto(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA));

        mockMvc.perform(post(URL_BASE + "/10/aceptar").with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA));
    }

    @Test
    void mutacionesSinTokenDevuelven401() throws Exception {
        mockMvc.perform(post(URL_BASE + "/10/aceptar")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL_BASE + "/10/rechazar")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL_BASE + "/10/cancelar")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------
    // Completar (POST, Tarea 4)
    // ------------------------------------------------------------------

    @Test
    void completarSinCuerpoONuloEsSinReasignaciones() throws Exception {
        when(servicioCompletar.completar(eq(1L), eq(10L), any())).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())).andExpect(status().isOk());
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasignaciones\":null}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SolicitudCompletarSucesion> solicitudes =
                ArgumentCaptor.forClass(SolicitudCompletarSucesion.class);
        verify(servicioCompletar, times(4)).completar(eq(1L), eq(10L), solicitudes.capture());
        assertThat(solicitudes.getAllValues()).allSatisfy(solicitud -> {
            assertThat(solicitud).isNotNull();
            assertThat(solicitud.reasignaciones()).isEmpty();
        });
    }

    @Test
    void completarConReasignacionesLasPasaAlServicio() throws Exception {
        when(servicioCompletar.completar(eq(1L), eq(10L), any())).thenReturn(PROPUESTA_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasignaciones\":[{\"idTarea\":200,\"idUsuario\":300},"
                                + "{\"idTarea\":201,\"idUsuario\":301}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPropuesta").value(10));

        verify(servicioCompletar).completar(1L, 10L, new SolicitudCompletarSucesion(List.of(
                new SolicitudReasignacion(200L, 300L), new SolicitudReasignacion(201L, 301L))));
    }

    @Test
    void completarConReasignacionInvalidaDevuelve400() throws Exception {
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasignaciones\":[{\"idTarea\":null,\"idUsuario\":300}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasignaciones\":[{\"idTarea\":200}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasignaciones\":[null]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicioCompletar);
    }

    @Test
    void completarTraduceRechazosYExigeToken() throws Exception {
        when(servicioCompletar.completar(eq(1L), eq(10L), any()))
                .thenThrow(new ExcepcionReglaNegocio("Quedan 1 tareas por resolver o reasignar"));

        mockMvc.perform(post(URL_BASE + "/10/completar").with(jwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Quedan 1 tareas por resolver o reasignar"));
        mockMvc.perform(post(URL_BASE + "/10/completar")).andExpect(status().isUnauthorized());
    }
}
