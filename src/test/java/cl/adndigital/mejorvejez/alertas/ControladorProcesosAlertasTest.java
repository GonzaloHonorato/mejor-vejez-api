package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionServicioExterno;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.interno.ResumenCorrida;
import cl.adndigital.mejorvejez.interno.ResumenReportes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;

/**
 * Prueba {@link ControladorProcesosAlertas} de punta a punta (controlador +
 * {@link ServicioProcesosAlertas} + autorización + auditoría, contra Oracle
 * real) con {@link ClienteServicios} simulado (Fase 9, TSA-62): las rutas
 * manuales del administrador reenvían la corrida y quedan auditadas, igual
 * que antes de mover la detección y el envío a los microservicios.
 *
 * <p>El id de ejecución que devuelve el cliente simulado siempre corresponde
 * a una fila real de {@code EJECUCION_PROCESO} (creada con {@link
 * RegistroEjecuciones}): la auditoría relee esa fila para agregar su {@code
 * idEstado} al detalle, así que un id inventado sin fila no sirve para
 * probar el camino {@code EXITO}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class ControladorProcesosAlertasTest {

    private static final String URL_DETECCION = "/api/administracion/procesos/deteccion-atrasos";
    private static final String URL_PREVENTIVAS = "/api/administracion/procesos/alertas-preventivas";
    private static final String URL_REPORTE = "/api/administracion/procesos/reporte-semanal";

    /** {@code EJECUCION_PROCESO.TIPO_EJECUCION} del reporte semanal (sin constante propia en el Core). */
    private static final String TIPO_EJECUCION_REPORTE_SEMANAL = "REPORTE_SEMANAL";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegistroEjecuciones registroEjecuciones;

    @MockitoBean
    private ClienteServicios cliente;

    @Test
    void deteccionManualReenviaYAudita() throws Exception {
        long idAdministrador = crearAdministrador();
        long idEjecucion = crearEjecucion(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS,
                CodigosTareas.ESTADO_EJECUCION_EXITOSA);
        when(cliente.deteccionAtrasos()).thenReturn(
                new ResumenCorrida(idEjecucion, 4, 1, "Alerta 9: sin destinatarios con canal operativo"));

        mockMvc.perform(post(URL_DETECCION).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(idEjecucion))
                .andExpect(jsonPath("$.creadas").value(4))
                .andExpect(jsonPath("$.errores").value(1))
                .andExpect(jsonPath("$.resumen").value("Alerta 9: sin destinatarios con canal operativo"));

        List<Map<String, Object>> eventos = eventosAuditados(String.valueOf(idEjecucion),
                AccionesAlertas.EJECUTAR_DETECCION_ATRASOS, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("EXITO");
        String detalles = detallesDe(eventos.get(0));
        assertThat(detalles).contains("\"idEstado\"").contains("\"creadas\":4").contains("\"errores\":1");
    }

    @Test
    void servicioCaidoDa503YAuditaFallo() throws Exception {
        long idAdministrador = crearAdministrador();
        when(cliente.deteccionAtrasos()).thenThrow(
                new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de alertas no está disponible."));

        mockMvc.perform(post(URL_DETECCION).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("El servicio de alertas no está disponible."));

        List<Map<String, Object>> eventos = eventosAuditados(CorridaAlertas.ID_ENTIDAD_SIN_EJECUCION,
                AccionesAlertas.EJECUTAR_DETECCION_ATRASOS, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("FALLO");
        // Nunca el texto "null": sin id de ejecución, la entidad auditada es SIN_EJECUCION.
        assertThat(eventos.get(0).get("ID_ENTIDAD")).isEqualTo(CorridaAlertas.ID_ENTIDAD_SIN_EJECUCION);
        assertThat(detallesDe(eventos.get(0))).contains("\"estadoHttp\":503");
    }

    @Test
    void reporteManualReenviaAReportes() throws Exception {
        long idAdministrador = crearAdministrador();
        LocalDate inicio = LocalDate.of(2026, 11, 2);
        long idEjecucion = crearEjecucion(TIPO_EJECUCION_REPORTE_SEMANAL, CodigosTareas.ESTADO_EJECUCION_EXITOSA);
        when(cliente.reporteSemanal(inicio)).thenReturn(new ResumenReportes(idEjecucion, 5, 4, 1, 2));

        mockMvc.perform(post(URL_REPORTE).param("inicio", inicio.toString())
                        .with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(idEjecucion))
                .andExpect(jsonPath("$.gruposProcesados").value(5))
                .andExpect(jsonPath("$.enviados").value(4))
                .andExpect(jsonPath("$.fallidos").value(1))
                .andExpect(jsonPath("$.omitidos").value(2));

        verify(cliente).reporteSemanal(inicio);
        List<Map<String, Object>> eventos = eventosAuditados(String.valueOf(idEjecucion),
                AccionesAlertas.EJECUTAR_REPORTE_SEMANAL, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("EXITO");
    }

    @Test
    void noAdministradorDa403() throws Exception {
        String uidColaborador = FixturaGrupos.uidUnico();
        new FixturaGrupos(jdbcTemplate).crearUsuario(uidColaborador, false);

        mockMvc.perform(post(URL_DETECCION).with(jwt().jwt(c -> c.subject(uidColaborador))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));

        verifyNoInteractions(cliente);
    }

    /** El 403 de no-administrador aplica igual a las tres rutas, no solo a detección. */
    @Test
    void noAdministradorDa403EnAlertasPreventivasYReporteSemanal() throws Exception {
        String uidColaborador = FixturaGrupos.uidUnico();
        new FixturaGrupos(jdbcTemplate).crearUsuario(uidColaborador, false);

        mockMvc.perform(post(URL_PREVENTIVAS).with(jwt().jwt(c -> c.subject(uidColaborador))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));
        mockMvc.perform(post(URL_REPORTE).with(jwt().jwt(c -> c.subject(uidColaborador))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));

        verifyNoInteractions(cliente);
    }

    // ------------------------------------------------------------------
    // Reenvío del cliente (ClienteServicios): propagación de 4xx, 503 e inicio
    // ------------------------------------------------------------------

    @Test
    void conflictoDelServicioDeReportesSePropagaComo409YAuditaFallo() throws Exception {
        long idAdministrador = crearAdministrador();
        when(cliente.reporteSemanal(isNull()))
                .thenThrow(new ExcepcionServicioExterno(HttpStatus.CONFLICT, "Ya hay un reporte semanal en ejecución."));

        mockMvc.perform(post(URL_REPORTE).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya hay un reporte semanal en ejecución."));

        List<Map<String, Object>> eventos = eventosAuditados(CorridaAlertas.ID_ENTIDAD_SIN_EJECUCION,
                AccionesAlertas.EJECUTAR_REPORTE_SEMANAL, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("FALLO");
        assertThat(detallesDe(eventos.get(0))).contains("\"estadoHttp\":409");
    }

    @Test
    void inicioQueNoEsLunesSePropagaComo400DesdeReportes() throws Exception {
        long idAdministrador = crearAdministrador();
        LocalDate martes = LocalDate.of(2026, 11, 3);
        when(cliente.reporteSemanal(martes))
                .thenThrow(new ExcepcionServicioExterno(HttpStatus.BAD_REQUEST, "El inicio del período debe ser un lunes."));

        mockMvc.perform(post(URL_REPORTE).param("inicio", martes.toString())
                        .with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El inicio del período debe ser un lunes."));
    }

    @Test
    void reporteSinInicioReenviaNulo() throws Exception {
        long idAdministrador = crearAdministrador();
        long idEjecucion = crearEjecucion(TIPO_EJECUCION_REPORTE_SEMANAL, CodigosTareas.ESTADO_EJECUCION_EXITOSA);
        when(cliente.reporteSemanal(isNull())).thenReturn(new ResumenReportes(idEjecucion, 0, 0, 0, 0));

        mockMvc.perform(post(URL_REPORTE).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk());

        verify(cliente).reporteSemanal(isNull());
    }

    @Test
    void alertasPreventivasManualesTambienReenviaYAudita() throws Exception {
        long idAdministrador = crearAdministrador();
        long idEjecucion = crearEjecucion(CodigosAlertas.TIPO_EJECUCION_ALERTAS_PREVENTIVAS,
                CodigosTareas.ESTADO_EJECUCION_EXITOSA);
        when(cliente.alertasPreventivas()).thenReturn(new ResumenCorrida(idEjecucion, 2, 0, null));

        mockMvc.perform(post(URL_PREVENTIVAS).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(idEjecucion))
                .andExpect(jsonPath("$.creadas").value(2))
                .andExpect(jsonPath("$.errores").value(0));

        List<Map<String, Object>> eventos = eventosAuditados(String.valueOf(idEjecucion),
                AccionesAlertas.EJECUTAR_ALERTAS_PREVENTIVAS, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("EXITO");
    }

    @Test
    void sinAutenticarDevuelve401() throws Exception {
        mockMvc.perform(post(URL_DETECCION)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Paridad de la auditoría con la versión anterior al retiro
    // ------------------------------------------------------------------

    /**
     * Aunque la llamada a Alertas respondió 200 (sin lanzar), si la
     * ejecución remota quedó {@code FALLIDA} (consultada de vuelta en
     * {@code EJECUCION_PROCESO}, que el propio microservicio ya cerró antes
     * de responder), la auditoría de la corrida manual es {@code FALLO}, no
     * {@code EXITO}.
     */
    @Test
    void ejecucionFallidaAuditaFalloAunqueElClienteNoLance() throws Exception {
        long idAdministrador = crearAdministrador();
        long idEjecucion = crearEjecucion(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS,
                CodigosTareas.ESTADO_EJECUCION_FALLIDA);
        when(cliente.deteccionAtrasos()).thenReturn(new ResumenCorrida(idEjecucion, 0, 1, "Proceso: Exception"));

        mockMvc.perform(post(URL_DETECCION).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk());

        List<Map<String, Object>> eventos = eventosAuditados(String.valueOf(idEjecucion),
                AccionesAlertas.EJECUTAR_DETECCION_ATRASOS, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("FALLO");
    }

    // ------------------------------------------------------------------
    // La relectura de la ejecución nunca tumba la respuesta al administrador
    // ------------------------------------------------------------------

    /**
     * El {@code idEjecucion} que trae la respuesta no tiene fila en {@code
     * EJECUCION_PROCESO} (p. ej. la relectura corre justo antes de que el
     * microservicio remoto termine de confirmarla): el administrador sigue
     * viendo 200, y la auditoría queda {@code EXITO} igual, con {@code
     * idEstado} en {@code null} en el detalle.
     */
    @Test
    void idEjecucionSinFilaNoRompeLaRespuestaYAuditaExito() throws Exception {
        long idAdministrador = crearAdministrador();
        long idEjecucionSinFila = 999_999_999L;
        when(cliente.deteccionAtrasos())
                .thenReturn(new ResumenCorrida(idEjecucionSinFila, 2, 0, null));

        mockMvc.perform(post(URL_DETECCION).with(jwt().jwt(c -> c.subject(uidDe(idAdministrador)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idEjecucion").value(idEjecucionSinFila));

        List<Map<String, Object>> eventos = eventosAuditados(String.valueOf(idEjecucionSinFila),
                AccionesAlertas.EJECUTAR_DETECCION_ATRASOS, idAdministrador);
        assertThat(eventos).hasSize(1);
        assertThat(resultadoDe(eventos.get(0))).isEqualTo("EXITO");
        assertThat(detallesDe(eventos.get(0))).contains("\"idEstado\":null");
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private long crearAdministrador() {
        String uid = FixturaGrupos.uidUnico();
        return new FixturaGrupos(jdbcTemplate).crearUsuario(uid, true);
    }

    /** Una {@code EJECUCION_PROCESO} real, ya cerrada en {@code estadoFinal}, para que la auditoría la relea. */
    private long crearEjecucion(String tipoEjecucion, String estadoFinal) {
        OffsetDateTime ahora = OffsetDateTime.now();
        long idEjecucion = registroEjecuciones.iniciar(tipoEjecucion, ahora.minusHours(1), ahora);
        registroEjecuciones.finalizar(idEjecucion, estadoFinal, 0, 0, null);
        return idEjecucion;
    }

    private String uidDe(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT UID_FIREBASE FROM USUARIO WHERE ID_USUARIO = ?", String.class,
                idUsuario);
    }

    private List<Map<String, Object>> eventosAuditados(String idEntidad, String accion, long idActor) {
        return jdbcTemplate.queryForList(
                "SELECT ea.ID_ENTIDAD, ce.CODIGO_ESTADO AS RESULTADO, "
                        + "DBMS_LOB.SUBSTR(ea.DETALLES_JSON, 4000, 1) AS DETALLES FROM EVENTO_AUDITORIA ea "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.TIPO_ENTIDAD = 'EJECUCION_PROCESO' AND ea.ID_ENTIDAD = ? "
                        + "AND ea.CODIGO_ACCION = ? AND ea.ID_USUARIO_ACTOR = ?",
                idEntidad, accion, idActor);
    }

    private String detallesDe(Map<String, Object> evento) {
        return (String) evento.get("DETALLES");
    }

    private String resultadoDe(Map<String, Object> evento) {
        return (String) evento.get("RESULTADO");
    }
}
