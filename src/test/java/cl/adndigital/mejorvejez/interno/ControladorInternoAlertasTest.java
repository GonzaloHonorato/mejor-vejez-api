package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.configuracion.FiltroClaveServicio;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Contrato HTTP de {@link ControladorInternoAlertas} y {@link
 * ControladorInternoEjecuciones} (Fase 9, TSA-61): los servicios son mocks,
 * así que estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON y la cadena interna de seguridad ({@code
 * ConfiguracionSeguridad#cadenaInterna}, {@link FiltroClaveServicio}), con
 * el mismo patrón que {@code configuracion.FiltroClaveServicioTest}.
 */
@WebMvcTest({ControladorInternoAlertas.class, ControladorInternoEjecuciones.class})
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
@TestPropertySource(properties = "CLAVE_SERVICIO=" + ControladorInternoAlertasTest.CLAVE_CORRECTA)
class ControladorInternoAlertasTest {

    static final String CLAVE_CORRECTA = "clave-de-prueba-interna-correcta";

    private static final String URL_ALERTAS = "/api/interno/alertas";
    private static final String URL_EJECUCIONES = "/api/interno/ejecuciones";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioInternoAlertas servicio;

    @MockitoBean
    private ServicioReservaIntentos servicioReserva;

    @MockitoBean
    private RegistroEjecuciones registro;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Camino feliz (con la clave correcta)
    // ------------------------------------------------------------------

    @Test
    void candidatosAtrasoPasaLosParametrosAlServicio() throws Exception {
        OffsetDateTime desde = OffsetDateTime.parse("2026-11-01T00:00:00-03:00");
        OffsetDateTime hasta = OffsetDateTime.parse("2026-11-02T10:00:00-03:00");
        when(servicio.candidatosAtraso(desde, hasta)).thenReturn(
                List.of(new CandidatoAtraso(5L, 1L, 3L, OffsetDateTime.parse("2026-11-02T09:50:00-03:00"), 30,
                        "PENDIENTE", true)));

        mockMvc.perform(get(URL_ALERTAS + "/candidatos-atraso").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO,
                        CLAVE_CORRECTA)
                        .param("desde", "2026-11-01T00:00:00-03:00")
                        .param("hasta", "2026-11-02T10:00:00-03:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idTarea").value(5))
                .andExpect(jsonPath("$[0].codigoEstado").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].generaAlerta").value(true));

        verify(servicio).candidatosAtraso(desde, hasta);
    }

    @Test
    void candidatosPreventivosPasaLosParametrosAlServicio() throws Exception {
        OffsetDateTime ahora = OffsetDateTime.parse("2026-11-02T10:00:00-03:00");
        when(servicio.candidatosPreventivos(ahora, 24, 3)).thenReturn(new CandidatosPreventivos(
                List.of(new CandidatoCita(7L, OffsetDateTime.parse("2026-11-03T09:00:00-03:00"))),
                List.of(new CandidatoReposicion(9L, LocalDate.of(2026, 11, 4)))));

        mockMvc.perform(get(URL_ALERTAS + "/candidatos-preventivos")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .param("ahora", "2026-11-02T10:00:00-03:00")
                        .param("horasCita", "24")
                        .param("diasReposicion", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citas[0].idTarea").value(7))
                .andExpect(jsonPath("$.reposiciones[0].idDetallePlan").value(9));

        verify(servicio).candidatosPreventivos(eq(ahora), eq(24), eq(3));
    }

    @Test
    void candidatosPreventivosConHorasCitaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get(URL_ALERTAS + "/candidatos-preventivos")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .param("ahora", "2026-11-02T10:00:00-03:00")
                        .param("horasCita", "-1")
                        .param("diasReposicion", "3"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void crearAlertaDevuelveLaRespuestaDelServicio() throws Exception {
        when(servicio.crearAlerta(new SolicitudCrearAlerta("ATRASO_TAREA", 5L, null)))
                .thenReturn(new RespuestaCrearAlerta(11L, true));

        mockMvc.perform(post(URL_ALERTAS).header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoAlerta\":\"ATRASO_TAREA\",\"idTarea\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idAlerta").value(11))
                .andExpect(jsonPath("$.creada").value(true));
    }

    @Test
    void enviosPendientesPasaElParametroAlServicio() throws Exception {
        OffsetDateTime desde = OffsetDateTime.parse("2026-11-01T00:00:00-03:00");
        when(servicioReserva.enviosPendientes(desde)).thenReturn(List.of(5L, 7L));

        mockMvc.perform(get(URL_ALERTAS + "/envios-pendientes").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO,
                        CLAVE_CORRECTA)
                        .param("desde", "2026-11-01T00:00:00-03:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(5))
                .andExpect(jsonPath("$[1]").value(7));

        verify(servicioReserva).enviosPendientes(desde);
    }

    @Test
    void reservarIntentosDevuelveLaRespuestaDelServicio() throws Exception {
        RespuestaReservaIntentos respuesta = new RespuestaReservaIntentos(
                new DatosAlertaCorreo(11L, 1L, "ATRASO_TAREA", "Mensaje de alerta"),
                List.of(new IntentoReservado(21L, 3L, "Ana", "CORREO", "ana@mejorvejez.test", 1)));
        when(servicioReserva.reservarIntentos(11L)).thenReturn(respuesta);

        mockMvc.perform(post(URL_ALERTAS + "/11/intentos/reservar")
                        .header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerta.idAlerta").value(11))
                .andExpect(jsonPath("$.intentos[0].idIntento").value(21))
                .andExpect(jsonPath("$.intentos[0].canal").value("CORREO"));

        verify(servicioReserva).reservarIntentos(11L);
    }

    @Test
    void crearAlertaTipoInvalidoDevuelve422() throws Exception {
        when(servicio.crearAlerta(new SolicitudCrearAlerta("INVALIDO", null, null)))
                .thenThrow(new ExcepcionReglaNegocio("El tipo de alerta 'INVALIDO' no es válido."));

        mockMvc.perform(post(URL_ALERTAS).header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoAlerta\":\"INVALIDO\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("El tipo de alerta 'INVALIDO' no es válido."));
    }

    @Test
    void iniciarEjecucionDevuelve201() throws Exception {
        // Jackson deserializa el cuerpo normalizando el offset a UTC (mismo instante, offset distinto): se
        // verifica con isAtSameInstantAs vía el capturador, no con el literal exacto -03:00 del JSON.
        when(registro.iniciar(eq("DETECCION_ATRASOS"), any(), any())).thenReturn(42L);

        mockMvc.perform(post(URL_EJECUCIONES).header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoEjecucion\":\"DETECCION_ATRASOS\","
                                + "\"inicioVentana\":\"2026-11-02T08:00:00-03:00\","
                                + "\"finVentana\":\"2026-11-02T10:00:00-03:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idEjecucion").value(42));

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(registro).iniciar(eq("DETECCION_ATRASOS"), captor.capture(), captor.capture());
        assertThat(captor.getAllValues().get(0)).isAtSameInstantAs(OffsetDateTime.parse("2026-11-02T08:00:00-03:00"));
        assertThat(captor.getAllValues().get(1)).isAtSameInstantAs(OffsetDateTime.parse("2026-11-02T10:00:00-03:00"));
    }

    @Test
    void finalizarEjecucionDevuelveLaRespuesta() throws Exception {
        RespuestaEjecucion respuesta = new RespuestaEjecucion(42L, "DETECCION_ATRASOS",
                OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), OffsetDateTime.parse("2026-11-02T10:00:00-03:00"),
                88L, OffsetDateTime.parse("2026-11-02T10:00:00-03:00"), OffsetDateTime.parse("2026-11-02T10:01:00-03:00"),
                2, 0, null);
        when(registro.respuesta(42L)).thenReturn(respuesta);

        mockMvc.perform(put(URL_EJECUCIONES + "/42").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO,
                        CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoEstado\":\"EXITOSA\",\"creadas\":2,\"errores\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(42))
                .andExpect(jsonPath("$.cantidadCreada").value(2));

        verify(registro).finalizar(42L, "EXITOSA", 2, 0, null);
    }

    // ------------------------------------------------------------------
    // Clave de servicio (regla de la Fase 9)
    // ------------------------------------------------------------------

    @Test
    void rutasAlertasExigenClave() throws Exception {
        mockMvc.perform(get(URL_ALERTAS + "/candidatos-atraso")
                        .param("desde", "2026-11-01T00:00:00-03:00")
                        .param("hasta", "2026-11-02T10:00:00-03:00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(get(URL_ALERTAS + "/candidatos-preventivos")
                        .param("ahora", "2026-11-02T10:00:00-03:00")
                        .param("horasCita", "24")
                        .param("diasReposicion", "3"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(post(URL_ALERTAS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoAlerta\":\"ATRASO_TAREA\",\"idTarea\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(get(URL_ALERTAS + "/envios-pendientes").param("desde", "2026-11-01T00:00:00-03:00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(post(URL_ALERTAS + "/11/intentos/reservar"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(post(URL_EJECUCIONES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoEjecucion\":\"DETECCION_ATRASOS\","
                                + "\"inicioVentana\":\"2026-11-02T08:00:00-03:00\","
                                + "\"finVentana\":\"2026-11-02T10:00:00-03:00\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        mockMvc.perform(put(URL_EJECUCIONES + "/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoEstado\":\"EXITOSA\",\"creadas\":2,\"errores\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        // Un JWT de usuario tampoco abre estas rutas: la cadena interna no configura oauth2ResourceServer.
        mockMvc.perform(get(URL_ALERTAS + "/candidatos-atraso")
                        .header("Authorization", "Bearer cualquier-jwt-de-usuario")
                        .param("desde", "2026-11-01T00:00:00-03:00")
                        .param("hasta", "2026-11-02T10:00:00-03:00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        verifyNoInteractions(servicio);
        verifyNoInteractions(servicioReserva);
        verifyNoInteractions(registro);
        verifyNoInteractions(jwtDecoder);
    }
}
