package cl.adndigital.mejorvejez.interno;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ExcepcionSolicitudInvalida;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.configuracion.FiltroClaveServicio;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorInternoReportes} (Fase 9, TSA-61): el
 * servicio es un mock, así que estas pruebas no tocan la base de datos, solo
 * el enlace de parámetros/JSON y la cadena interna de seguridad ({@code
 * ConfiguracionSeguridad#cadenaInterna}, {@link FiltroClaveServicio}), mismo
 * patrón que {@code ControladorInternoAlertasTest}.
 */
@WebMvcTest(ControladorInternoReportes.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
@TestPropertySource(properties = "CLAVE_SERVICIO=" + ControladorInternoReportesTest.CLAVE_CORRECTA)
class ControladorInternoReportesTest {

    static final String CLAVE_CORRECTA = "clave-de-prueba-interna-correcta";

    private static final String URL = "/api/interno/reportes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioInternoReportes servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Camino feliz (con la clave correcta)
    // ------------------------------------------------------------------

    @Test
    void gruposDevuelveLaRespuestaDelServicio() throws Exception {
        when(servicio.grupos()).thenReturn(List.of(
                new GrupoReporte(1L, "Grupo Pérez", List.of(new Destinatario(3L, "Ana", "ana@mejorvejez.test")))));

        mockMvc.perform(get(URL + "/grupos").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idGrupo").value(1))
                .andExpect(jsonPath("$[0].destinatarios[0].correo").value("ana@mejorvejez.test"));
    }

    @Test
    void semanaPasaLosParametrosAlServicio() throws Exception {
        LocalDate inicio = LocalDate.of(2026, 11, 2);
        when(servicio.semana(7L, inicio)).thenReturn(
                new DatosSemana(7L, "Grupo Pérez", inicio, inicio.plusDays(6), List.of(), List.of(), List.of(),
                        List.of()));

        mockMvc.perform(get(URL + "/grupos/{idGrupo}/semana", 7L)
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .param("inicio", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idGrupo").value(7))
                .andExpect(jsonPath("$.nombreGrupo").value("Grupo Pérez"))
                .andExpect(jsonPath("$.fin").value("2026-11-08"));

        verify(servicio).semana(eq(7L), eq(inicio));
    }

    @Test
    void semanaConGrupoInexistenteDevuelve404() throws Exception {
        when(servicio.semana(999L, LocalDate.of(2026, 11, 2)))
                .thenThrow(new ExcepcionNoEncontrado("El grupo no existe."));

        mockMvc.perform(get(URL + "/grupos/{idGrupo}/semana", 999L)
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .param("inicio", "2026-11-02"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El grupo no existe."));
    }

    @Test
    void semanaConInicioNoLunesDevuelve400() throws Exception {
        when(servicio.semana(7L, LocalDate.of(2026, 11, 3)))
                .thenThrow(new ExcepcionSolicitudInvalida("El inicio del período debe ser un lunes."));

        mockMvc.perform(get(URL + "/grupos/{idGrupo}/semana", 7L)
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .param("inicio", "2026-11-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.detail").value("El inicio del período debe ser un lunes."));
    }

    @Test
    void reservarEnvioConInicioNoLunesDevuelve400() throws Exception {
        when(servicio.reservarEnvio(new SolicitudReservarEnvio(7L, LocalDate.of(2026, 11, 3))))
                .thenThrow(new ExcepcionSolicitudInvalida("El inicio del período debe ser un lunes."));

        mockMvc.perform(post(URL + "/envios").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idGrupo\":7,\"inicioPeriodo\":\"2026-11-03\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.detail").value("El inicio del período debe ser un lunes."));
    }

    @Test
    void reservarEnvioConInicioNuloDevuelve400() throws Exception {
        mockMvc.perform(post(URL + "/envios").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idGrupo\":7,\"inicioPeriodo\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"));

        verifyNoInteractions(servicio);
    }

    @Test
    void registrarResultadoConElementoNuloEnResultadosDevuelve400() throws Exception {
        mockMvc.perform(put(URL + "/envios/11/resultado")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultados\":[{\"idUsuario\":3,\"enviado\":true}, null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"));

        verifyNoInteractions(servicio);
    }

    @Test
    void reservarEnvioDevuelveLaRespuestaDelServicio() throws Exception {
        when(servicio.reservarEnvio(new SolicitudReservarEnvio(7L, LocalDate.of(2026, 11, 2))))
                .thenReturn(new RespuestaReservaEnvio(11L, "PENDIENTE", 0,
                        List.of(new Destinatario(3L, "Ana", "ana@mejorvejez.test"))));

        mockMvc.perform(post(URL + "/envios").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idGrupo\":7,\"inicioPeriodo\":\"2026-11-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEnvio").value(11))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.pendientes[0].idUsuario").value(3));
    }

    @Test
    void reservarEnvioConGrupoInexistenteDevuelve404() throws Exception {
        when(servicio.reservarEnvio(new SolicitudReservarEnvio(999L, LocalDate.of(2026, 11, 2))))
                .thenThrow(new ExcepcionNoEncontrado("El grupo no existe."));

        mockMvc.perform(post(URL + "/envios").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idGrupo\":999,\"inicioPeriodo\":\"2026-11-02\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registrarResultadoDevuelveLaRespuestaDelServicio() throws Exception {
        when(servicio.registrarResultado(11L,
                new SolicitudResultadoEnvio(List.of(new ResultadoDestinatario(3L, true, null)))))
                .thenReturn(new RespuestaResultadoEnvio("ENVIADO"));

        mockMvc.perform(put(URL + "/envios/11/resultado")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultados\":[{\"idUsuario\":3,\"enviado\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENVIADO"));
    }

    @Test
    void registrarResultadoSobreEnvioCerradoDevuelve409() throws Exception {
        when(servicio.registrarResultado(11L,
                new SolicitudResultadoEnvio(List.of(new ResultadoDestinatario(3L, true, null)))))
                .thenThrow(new ExcepcionConflicto("El envío ya está cerrado (ENVIADO o FALLIDO)."));

        mockMvc.perform(put(URL + "/envios/11/resultado")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultados\":[{\"idUsuario\":3,\"enviado\":true}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void registrarResultadoConUsuarioAjenoDevuelve422() throws Exception {
        when(servicio.registrarResultado(11L,
                new SolicitudResultadoEnvio(List.of(new ResultadoDestinatario(99L, true, null)))))
                .thenThrow(new ExcepcionReglaNegocio("El usuario 99 no pertenece a la foto de este envío."));

        mockMvc.perform(put(URL + "/envios/11/resultado")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultados\":[{\"idUsuario\":99,\"enviado\":true}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------
    // Clave de servicio (regla de la Fase 9)
    // ------------------------------------------------------------------

    @Test
    void rutasReportesExigenClave() throws Exception {
        mockMvc.perform(get(URL + "/grupos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(get(URL + "/grupos/{idGrupo}/semana", 7L).param("inicio", "2026-11-02"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(post(URL + "/envios").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idGrupo\":7,\"inicioPeriodo\":\"2026-11-02\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(put(URL + "/envios/11/resultado").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultados\":[{\"idUsuario\":3,\"enviado\":true}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        // Un JWT de usuario tampoco abre estas rutas: la cadena interna no configura oauth2ResourceServer.
        mockMvc.perform(get(URL + "/grupos").header("Authorization", "Bearer cualquier-jwt-de-usuario"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        verifyNoInteractions(servicio);
        verifyNoInteractions(jwtDecoder);
    }
}
