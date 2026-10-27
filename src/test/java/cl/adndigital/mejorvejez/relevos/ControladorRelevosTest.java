package cl.adndigital.mejorvejez.relevos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorRelevos} (HU-11, TSA-51). El servicio
 * es un mock: estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON, la traducción de excepciones y la validación del cuerpo,
 * igual que {@code auditoria.ControladorAuditoriaTest}.
 */
@WebMvcTest(ControladorRelevos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorRelevosTest {

    private static final String URL_BASE = "/api/grupos/1/relevos";

    private static final RespuestaRelevo RELEVO_DE_EJEMPLO = new RespuestaRelevo(10L, 1L, "Grupo", 2L, "Adulto",
            new RespuestaPersona(3L, "Saliente"), new RespuestaPersona(4L, "Entrante"),
            new RespuestaPersona(3L, "Saliente"), OffsetDateTime.now(), "Notas", 1L, "COMPLETADA", 0, 0, List.of());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioRelevos servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Registrar (POST)
    // ------------------------------------------------------------------

    @Test
    void registrarDevuelve201ConLocation() throws Exception {
        when(servicio.registrar(eq(1L), any(SolicitudRegistrarRelevo.class))).thenReturn(RELEVO_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdulto\":2,\"idUsuarioSaliente\":3,\"idUsuarioEntrante\":4,\"notas\":\"ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/1/relevos/10"));
    }

    @Test
    void registrarSinTokenDevuelve401() throws Exception {
        mockMvc.perform(post(URL_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdulto\":2,\"idUsuarioSaliente\":3,\"idUsuarioEntrante\":4}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void registrarSinAdultoDevuelve400() throws Exception {
        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idUsuarioSaliente\":3,\"idUsuarioEntrante\":4}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void registrarConNotasDeMasDe2000CaracteresDevuelve400() throws Exception {
        String notasLargas = "a".repeat(2001);
        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdulto\":2,\"idUsuarioSaliente\":3,\"idUsuarioEntrante\":4,\"notas\":\""
                                + notasLargas + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void registrarSinPermisoDevuelve403() throws Exception {
        when(servicio.registrar(eq(1L), any(SolicitudRegistrarRelevo.class)))
                .thenThrow(new ExcepcionAccesoDenegado("No perteneces a este grupo"));

        mockMvc.perform(post(URL_BASE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdulto\":2,\"idUsuarioSaliente\":3,\"idUsuarioEntrante\":4}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Listar (GET)
    // ------------------------------------------------------------------

    @Test
    void listarPasaFiltroYPaginaAlServicio() throws Exception {
        when(servicio.listar(1L, 2L, 0, 20)).thenReturn(new RespuestaPagina<>(List.of(), 0, 20, 0));

        mockMvc.perform(get(URL_BASE).with(jwt()).param("idAdulto", "2"))
                .andExpect(status().isOk());

        verify(servicio).listar(1L, 2L, 0, 20);
    }

    @Test
    void listarTamanoSuperiorA100SeLimitaA100() throws Exception {
        when(servicio.listar(1L, null, 0, 100)).thenReturn(new RespuestaPagina<>(List.of(), 0, 100, 0));

        mockMvc.perform(get(URL_BASE).with(jwt()).param("tamano", "500"))
                .andExpect(status().isOk());

        verify(servicio).listar(1L, null, 0, 100);
    }

    @Test
    void listarPaginaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get(URL_BASE).with(jwt()).param("pagina", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get(URL_BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------
    // Obtener (GET /{idRelevo})
    // ------------------------------------------------------------------

    @Test
    void obtenerPasaLosIdsAlServicio() throws Exception {
        when(servicio.obtener(1L, 10L)).thenReturn(RELEVO_DE_EJEMPLO);

        mockMvc.perform(get(URL_BASE + "/10").with(jwt())).andExpect(status().isOk());

        verify(servicio).obtener(1L, 10L);
    }

    @Test
    void obtenerSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get(URL_BASE + "/10")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------
    // Cancelar (POST /{idRelevo}/cancelar)
    // ------------------------------------------------------------------

    @Test
    void cancelarLlamaAlServicio() throws Exception {
        when(servicio.cancelar(1L, 10L)).thenReturn(RELEVO_DE_EJEMPLO);

        mockMvc.perform(post(URL_BASE + "/10/cancelar").with(jwt())).andExpect(status().isOk());

        verify(servicio).cancelar(1L, 10L);
    }

    @Test
    void cancelarSinTokenDevuelve401() throws Exception {
        mockMvc.perform(post(URL_BASE + "/10/cancelar")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }
}
