package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.notificaciones.CodigosNotificaciones;
import cl.adndigital.mejorvejez.notificaciones.EnviadorCorreo;
import cl.adndigital.mejorvejez.notificaciones.ResultadoEnvio;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Prueba el detector de alertas preventivas (TSA-44, reglas 1, 3, 4, 5, 6,
 * 10 y 11 de la Fase 5) contra Oracle real (Testcontainers), con el reloj
 * fijo en el lunes 2026-11-02 10:00 de Santiago (mismo instante que {@code
 * ServicioDeteccionAtrasosTest}, en un rango de ids de usuario distinto).
 * Cada prueba crea su propio grupo (con un coordinador de id > 127), adulto
 * y tareas/detalles, y corre {@code ejecutar(..., idGrupo)}: la BD es
 * compartida.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAlertasPreventivasTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00). */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** "Hoy" en Santiago al momento de {@link #AHORA}. */
    private static final LocalDate HOY = AHORA.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();

    /** Ids de usuario explícitos > 127, en un rango propio (distinto del de las otras pruebas). */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(981_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioAlertasPreventivas servicio;

    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private EnviadorCorreo enviadorCorreo;

    @MockitoSpyBean
    private RepositorioConsultaAlertas consultaAlertas;

    @MockitoSpyBean
    private ServicioNotificacionAlertas servicioNotificacion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void preparar() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
        doReturn(CodigosNotificaciones.CANAL_CORREO).when(enviadorCorreo).canal();
        doReturn(true).when(enviadorCorreo).disponible();
        doReturn(ResultadoEnvio.exitoso("<" + UUID.randomUUID() + "@mejorvejez.test>")).when(enviadorCorreo)
                .enviar(any());
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Cita próxima (TSA-44, regla 3)
    // ------------------------------------------------------------------

    @Test
    void citaDentroDe24hGeneraAlertaConMedicoYLugar() {
        Escenario e = escenario();
        OffsetDateTime horario = AHORA.plusDays(1).withHour(9).withMinute(0); // martes 09:00 (dentro de 24h)
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);
        fixturaTareas.crearCita(idTarea, "Dr. Fixtura", "Clínica Fixtura", null);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isZero();
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        Map<String, Object> alerta = alertas.get(0);
        assertThat(alerta.get("CLAVE_ALERTA")).isEqualTo("CIT-" + idTarea);
        assertThat(alerta.get("TIPO_ALERTA")).isEqualTo(CodigosAlertas.TIPO_CITA_PROXIMA);
        assertThat(alerta.get("MENSAJE_ALERTA")).isEqualTo("Adulto fixtura tiene «Tarea fixtura manual» el "
                + "03-11-2026 a las 09:00. Médico: Dr. Fixtura. Lugar: Clínica Fixtura.");
        assertThat(alerta.get("ID_DETALLE_PLAN")).isNull();
    }

    @Test
    void citaAMasDe24hNoGenera() {
        Escenario e = escenario();
        OffsetDateTime horario = AHORA.plusDays(1).withHour(10).withMinute(1); // martes 10:01: 24h y 1 min
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).isEmpty();
    }

    @Test
    void citaYaPasadaOCanceladaNoGenera() {
        Escenario e = escenario();
        long idPasada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(1));
        long idCancelada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idCancelada, CodigosTareas.ESTADO_TAREA_CANCELADA);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idPasada)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idCancelada)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Reposición de medicamento (TSA-44, regla 4)
    // ------------------------------------------------------------------

    @Test
    void reposicionTresDiasAntesGenera() {
        Escenario e = escenario();
        LocalDate termino = LocalDate.of(2026, 11, 5); // hoy + 3 días
        long idDetalle = detalleConTermino(e, termino);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isZero();
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDelDetalle(idDetalle);
        assertThat(alertas).hasSize(1);
        Map<String, Object> alerta = alertas.get(0);
        assertThat(alerta.get("CLAVE_ALERTA")).isEqualTo("REP-" + idDetalle + "-20261105");
        assertThat(alerta.get("TIPO_ALERTA")).isEqualTo(CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO);
        assertThat(alerta.get("ID_TAREA")).isNull();
        OffsetDateTime fechaProgramada = jdbcTemplate.queryForObject(
                "SELECT FECHA_PROGRAMADA FROM ALERTA_CUIDADO WHERE ID_ALERTA = ?", OffsetDateTime.class,
                numero(alerta, "ID_ALERTA"));
        assertThat(fechaProgramada.toInstant())
                .isEqualTo(ZonedDateTime.of(termino, LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toInstant());
    }

    @Test
    void reposicionCuatroDiasAntesNoGenera() {
        Escenario e = escenario();
        long idDetalle = detalleConTermino(e, LocalDate.of(2026, 11, 6)); // hoy + 4 días

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalle)).isEmpty();
    }

    @Test
    void reposicionConTerminoVencidoNoGenera() {
        Escenario e = escenario();
        long idDetalle = detalleConTermino(e, HOY.minusDays(1)); // término ya vencido

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalle)).isEmpty();
    }

    @Test
    void reposicionNoCreaTareas() {
        Escenario e = escenario();
        long idDetalle = detalleConTermino(e, HOY); // término hoy mismo: dentro de la ventana

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(fixturaTareas.tareasDelDetalle(idDetalle)).isEmpty();
    }

    @Test
    void reposicionSinTerminoODetalleInactivoNoGenera() {
        Escenario sinTermino = escenario();
        long idPlanSinTermino = fixturaPlanes.crearPlan(sinTermino.idGrupo, sinTermino.idAdulto,
                sinTermino.idCoordinador, "ACTIVO");
        long idDetalleSinTermino = fixturaPlanes.crearDetalle(idPlanSinTermino,
                fixturaPlanes.crearMedicamento("Losartán"), "ACTIVO");

        Escenario detalleInactivo = escenario();
        long idDetalleInactivo = detalleConTermino(detalleInactivo, HOY.plusDays(2));
        fixturaPlanes.cambiarEstadoDetalle(idDetalleInactivo, "INACTIVO");

        assertThat(servicio.ejecutar(AHORA, sinTermino.idGrupo).cantidadCreada()).isZero();
        assertThat(servicio.ejecutar(AHORA, detalleInactivo.idGrupo).cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalleSinTermino)).isEmpty();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalleInactivo)).isEmpty();
    }

    /**
     * Regla 5 (Ronda de corrección 1): un detalle desactivado DESPUÉS de
     * obtener el id del plan (sin bloqueo, {@code
     * RepositorioConsultaAlertas#planDeDetalle}) pero ANTES de bloquearlo no
     * genera alerta: la carga del detalle, bajo el bloqueo del plan, debe
     * ver el estado vigente, no una foto de antes del bloqueo.
     *
     * <p>Nota de diseño: el punto de inyección del brief ("un spy que, al
     * devolver los candidatos, desactive el detalle", igual que {@code
     * ServicioDeteccionAtrasosTest#tareaConfirmadaDespuesDeLaConsultaNoGeneraAlerta}
     * de la Tarea 5) no reproduce la carrera de la regla 5 aquí: cada
     * llamada a {@code crearReposicion} abre su propia transacción {@code
     * REQUIRES_NEW} con un contexto de persistencia nuevo, así que si la
     * mutación ya está confirmada ANTES de que esa transacción empiece (como
     * pasaría mutando justo al devolver los candidatos, antes de llamar a
     * {@code crearReposicion}), la primera lectura de esa transacción ya la
     * ve — con o sin el error de caché. Lo comprobé empíricamente: esa
     * variante pasaba incluso contra el código con el error (sin RED). La
     * carrera real ocurre DENTRO de esa transacción, entre la proyección
     * escalar sin bloqueo y el bloqueo del plan, así que el spy intercepta
     * {@code planDeDetalle} (el primer paso de {@code crearReposicion}) para
     * mutar justo en esa ventana.
     */
    @Test
    void detalleDesactivadoDespuesDeLaConsultaNoGeneraReposicion() {
        Escenario e = escenario();
        long idDetalle = detalleConTermino(e, HOY.plusDays(1));
        doAnswer(invocacion -> {
            Object idPlan = invocacion.callRealMethod();
            fixturaPlanes.cambiarEstadoDetalle(idDetalle, "INACTIVO");
            return idPlan;
        }).when(consultaAlertas).planDeDetalle(idDetalle);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        verify(consultaAlertas).planDeDetalle(idDetalle);
        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalle)).isEmpty();
    }

    @Test
    void cambioDeTerminoGeneraNuevaAlertaDeReposicion() {
        Escenario e = escenario();
        LocalDate primerTermino = LocalDate.of(2026, 11, 5);
        long idDetalle = detalleConTermino(e, primerTermino);

        RespuestaEjecucion primera = servicio.ejecutar(AHORA, e.idGrupo);

        LocalDate segundoTermino = LocalDate.of(2026, 11, 3);
        fixturaPlanes.cambiarTermino(idDetalle, segundoTermino);

        RespuestaEjecucion segunda = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(primera.cantidadCreada()).isEqualTo(1);
        assertThat(segunda.cantidadCreada()).isEqualTo(1);
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDelDetalle(idDetalle);
        assertThat(alertas).hasSize(2);
        assertThat(alertas).extracting(a -> a.get("CLAVE_ALERTA"))
                .containsExactlyInAnyOrder("REP-" + idDetalle + "-20261105", "REP-" + idDetalle + "-20261103");
    }

    // ------------------------------------------------------------------
    // Idempotencia y resultado de la corrida (reglas 1, 5, 11, 12)
    // ------------------------------------------------------------------

    @Test
    void reejecutarNoDuplicaNingunaPreventiva() {
        Escenario e = escenario();
        long idTareaCita = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.plusHours(1));
        long idDetalle = detalleConTermino(e, HOY.plusDays(1));

        RespuestaEjecucion primera = servicio.ejecutar(AHORA, e.idGrupo);
        RespuestaEjecucion segunda = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(primera.cantidadCreada()).isEqualTo(2);
        assertThat(primera.cantidadErrores()).isZero();
        assertThat(segunda.cantidadCreada()).isZero();
        assertThat(segunda.cantidadErrores()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTareaCita)).hasSize(1);
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalle)).hasSize(1);
    }

    @Test
    void corridaRegistraAlertasPreventivas() {
        Escenario e = escenario();

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosAlertas.TIPO_EJECUCION_ALERTAS_PREVENTIVAS);
        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(AHORA);
        assertThat(respuesta.finVentana()).isAtSameInstantAs(AHORA.plusHours(24));
        assertThat(respuesta.fechaFin()).isNotNull();
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        verify(servicioNotificacion).notificarPendientes(eq(AHORA.minusHours(48)), eq(e.idGrupo));
    }

    // ------------------------------------------------------------------
    // Corrida manual (regla 12)
    // ------------------------------------------------------------------

    @Test
    void ejecutarManualExigeAdministradorYAudita() {
        String uidNoAdministrador = FixturaGrupos.uidUnico();
        long idNoAdministrador = fixturaGrupos.crearUsuario(uidNoAdministrador, false);
        autenticar(uidNoAdministrador);
        assertThatThrownBy(() -> servicio.ejecutarManual()).isInstanceOf(ExcepcionAccesoDenegado.class);
        List<Map<String, Object>> rechazos = jdbcTemplate.queryForList(
                "SELECT ce.CODIGO_ESTADO AS RESULTADO FROM EVENTO_AUDITORIA ea "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.CODIGO_ACCION = 'ACCESO_ADMINISTRADOR' AND ea.ID_USUARIO_ACTOR = ?",
                idNoAdministrador);
        assertThat(rechazos).extracting(fila -> fila.get("RESULTADO")).containsExactly("RECHAZADA");

        String uidAdministrador = FixturaGrupos.uidUnico();
        long idAdministrador = fixturaGrupos.crearUsuario(uidAdministrador, true);
        autenticar(uidAdministrador);
        // La corrida manual es global (sin grupo): se deja vacía para no
        // recorrer ni escribir la BD compartida.
        doReturn(List.of()).when(consultaAlertas).citasProximasCandidatas(any(), any(), isNull());
        doReturn(List.of()).when(consultaAlertas).detallesPorReponer(any(), isNull());
        doReturn(ResultadoNotificacion.vacio()).when(servicioNotificacion).notificarPendientes(any(), isNull());

        RespuestaEjecucion respuesta = servicio.ejecutarManual();

        verify(consultaAlertas).citasProximasCandidatas(eq(AHORA), eq(AHORA.plusHours(24)), isNull());
        verify(consultaAlertas).detallesPorReponer(eq(HOY), isNull());
        verify(servicioNotificacion).notificarPendientes(eq(AHORA.minusHours(48)), isNull());
        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosAlertas.TIPO_EJECUCION_ALERTAS_PREVENTIVAS);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ea.ID_USUARIO_ACTOR, ea.ID_GRUPO, ea.NOMBRE_SERVICIO, ce.CODIGO_ESTADO AS RESULTADO "
                        + "FROM EVENTO_AUDITORIA ea JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.TIPO_ENTIDAD = 'EJECUCION_PROCESO' AND ea.ID_ENTIDAD = ? AND ea.CODIGO_ACCION = ?",
                String.valueOf(respuesta.idEjecucion()), AccionesAlertas.EJECUTAR_ALERTAS_PREVENTIVAS);
        assertThat(eventos).hasSize(1);
        assertThat(numero(eventos.get(0), "ID_USUARIO_ACTOR")).isEqualTo(idAdministrador);
        assertThat(eventos.get(0).get("ID_GRUPO")).isNull();
        assertThat(eventos.get(0).get("NOMBRE_SERVICIO")).isEqualTo(CodigosAlertas.MODULO);
        assertThat(eventos.get(0).get("RESULTADO")).isEqualTo("EXITO");
    }

    // ------------------------------------------------------------------
    // Escenario y ayudantes
    // ------------------------------------------------------------------

    /** Grupo ACTIVO con un coordinador ACTIVO (id > 127) y un adulto ACTIVO. */
    private Escenario escenario() {
        long idCoordinador = insertarUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        return new Escenario(idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(long idCoordinador, long idGrupo, long idAdulto) {
    }

    /** Plan y detalle ACTIVO del escenario, con la {@code FECHA_ESTIMADA_TERMINO} indicada. */
    private long detalleConTermino(Escenario e, LocalDate termino) {
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartán");
        return fixturaPlanes.crearDetalleConTermino(idPlan, idMedicamento, termino);
    }

    private long insertarUsuarioAlto() {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', (SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = 'ACTIVO'))",
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + UUID.randomUUID() + "@alertas.mejorvejez.test",
                "Usuario " + idUsuario);
        return idUsuario;
    }

    private long idEstadoEjecucion(String codigo) {
        return idEstado(CodigosTareas.TABLA_EJECUCION_PROCESO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
    }

    private static long numero(Map<String, Object> fila, String columna) {
        return ((Number) fila.get(columna)).longValue();
    }

    private void autenticar(String uid) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject(uid)
                .claim("email", uid + "@ejemplo.cl")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
