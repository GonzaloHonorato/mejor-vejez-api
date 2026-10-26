package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Prueba {@link ServicioEstadoTareas} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 10:00
 * (America/Santiago), igual que {@link ServicioConsultaTareasTest}. Datos
 * propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioEstadoTareasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioEstadoTareas servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;
    private FixturaGeneracion fixturaGeneracion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaGeneracion = new FixturaGeneracion(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Transiciones (regla 10 de la Fase 4, TSA-40)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("transicionesValidas")
    void transicionesValidas(String estadoInicial, String estadoDestino) {
        Escenario e = escenario();
        long idTarea = tareaManual(e, estadoInicial);
        long idEstadoDestino = idEstado("TAREA_CUIDADO", estadoDestino);
        String observacion = exigeObservacion(estadoDestino) ? "Observación de la prueba" : null;

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEstadoDestino, observacion));

        assertThat(respuesta.idEstado()).isEqualTo(idEstadoDestino);
    }

    static Stream<Arguments> transicionesValidas() {
        return Stream.of(
                Arguments.of(CodigosTareas.ESTADO_TAREA_PENDIENTE, CodigosTareas.ESTADO_TAREA_EN_CURSO),
                Arguments.of(CodigosTareas.ESTADO_TAREA_PENDIENTE, CodigosTareas.ESTADO_TAREA_COMPLETADA),
                Arguments.of(CodigosTareas.ESTADO_TAREA_PENDIENTE, CodigosTareas.ESTADO_TAREA_NO_REALIZADA),
                Arguments.of(CodigosTareas.ESTADO_TAREA_PENDIENTE, CodigosTareas.ESTADO_TAREA_CANCELADA),
                Arguments.of(CodigosTareas.ESTADO_TAREA_EN_CURSO, CodigosTareas.ESTADO_TAREA_COMPLETADA),
                Arguments.of(CodigosTareas.ESTADO_TAREA_EN_CURSO, CodigosTareas.ESTADO_TAREA_NO_REALIZADA));
    }

    @ParameterizedTest
    @MethodSource("transicionesInvalidas")
    void transicionesInvalidas(String estadoInicial, String estadoDestino) {
        Escenario e = escenario();
        long idTarea = tareaManual(e, estadoInicial);
        long idEstadoDestino = idEstado("TAREA_CUIDADO", estadoDestino);
        String descripcionActual = descripcionEstado("TAREA_CUIDADO", estadoInicial);
        String descripcionNueva = descripcionEstado("TAREA_CUIDADO", estadoDestino);

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEstadoDestino, "Observación de la prueba")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("No se puede pasar de «" + descripcionActual + "» a «" + descripcionNueva + "»");
    }

    static Stream<Arguments> transicionesInvalidas() {
        return Stream.of(
                Arguments.of(CodigosTareas.ESTADO_TAREA_PENDIENTE, CodigosTareas.ESTADO_TAREA_PENDIENTE),
                Arguments.of(CodigosTareas.ESTADO_TAREA_EN_CURSO, CodigosTareas.ESTADO_TAREA_EN_CURSO),
                Arguments.of(CodigosTareas.ESTADO_TAREA_EN_CURSO, CodigosTareas.ESTADO_TAREA_CANCELADA),
                Arguments.of(CodigosTareas.ESTADO_TAREA_COMPLETADA, CodigosTareas.ESTADO_TAREA_PENDIENTE),
                Arguments.of(CodigosTareas.ESTADO_TAREA_NO_REALIZADA, CodigosTareas.ESTADO_TAREA_EN_CURSO),
                Arguments.of(CodigosTareas.ESTADO_TAREA_CANCELADA, CodigosTareas.ESTADO_TAREA_COMPLETADA));
    }

    // ------------------------------------------------------------------
    // Confirmación (COMPLETADA / NO_REALIZADA) y observación
    // ------------------------------------------------------------------

    @Test
    void registroDeConfirmacionYObservacion() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idCompletada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_COMPLETADA);
        OffsetDateTime ahora = HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCompletada, "Todo en orden"));

        assertThat(respuesta.idEstado()).isEqualTo(idCompletada);
        assertThat(respuesta.observacionCierre()).isEqualTo("Todo en orden");
        assertThat(respuesta.nombreConfirmador()).isNotBlank();
        assertThat(respuesta.fechaConfirmacion()).isEqualTo(ahora);

        List<Map<String, Object>> historial = jdbcTemplate.queryForList(
                "SELECT ID_ESTADO_ANTERIOR, ID_USUARIO_CAMBIO, OBSERVACION FROM HISTORIAL_ESTADO_TAREA "
                        + "WHERE ID_TAREA = ? AND ID_ESTADO_NUEVO = ?",
                idTarea, idCompletada);
        assertThat(historial).hasSize(1);
        assertThat(((Number) historial.get(0).get("ID_USUARIO_CAMBIO")).longValue()).isEqualTo(e.idCoordinador);
        assertThat(historial.get(0).get("OBSERVACION")).isEqualTo("Todo en orden");
    }

    @Test
    void noRealizadaExigeObservacion() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idNoRealizada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_NO_REALIZADA);

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idNoRealizada, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Indica una observación");
    }

    @Test
    void noRealizadaEsFinalYNoQuedaAtrasada() {
        Escenario e = escenario();
        // Horario bien en el pasado: si la tarea siguiera abierta, estaría atrasada (regla 12).
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY.minusDays(1), 8, 0));
        long idNoRealizada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_NO_REALIZADA);

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idNoRealizada, "No se pudo realizar"));

        assertThat(respuesta.idEstado()).isEqualTo(idNoRealizada);
        assertThat(respuesta.atrasada()).isFalse();
    }

    @Test
    void motivoCancelacionSuperaMaximoDevuelve422() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idCancelada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_CANCELADA);
        String motivoLargo = "x".repeat(201);

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCancelada, motivoLargo)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El motivo de cancelación admite hasta 200 caracteres");
    }

    /** Borde exacto: 200 caracteres se acepta (el límite es "hasta 200", no "menos de 200"). */
    @Test
    void motivoCancelacionDeExactamente200CaracteresSeAcepta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idCancelada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_CANCELADA);
        String motivoLimite = "x".repeat(200);

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCancelada, motivoLimite));

        assertThat(respuesta.idEstado()).isEqualTo(idCancelada);
        assertThat(respuesta.motivoCancelacion()).hasSize(200);
    }

    // ------------------------------------------------------------------
    // El estado debe pertenecer al dominio TAREA_CUIDADO.ESTADO
    // ------------------------------------------------------------------

    @Test
    void estadoDeOtroDominio422() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idEstadoDeOtroDominio = idEstado("PLAN_MEDICAMENTOS", "ACTIVO");

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEstadoDeOtroDominio, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El estado indicado no corresponde a TAREA_CUIDADO.ESTADO");
    }

    /**
     * Anexo 3 de la spec: el estado debe existir, estar ACTIVO y ser del
     * dominio. Un estado de {@code TAREA_CUIDADO.ESTADO} desactivado en el
     * catálogo responde 422 (antes, un NPE que terminaba en 500). La fila de
     * prueba es propia (código único) y se borra al final.
     */
    @Test
    void estadoDesactivadoDelCatalogo422() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        String codigo = "OBS_" + Long.toString(System.nanoTime(), 36).toUpperCase();
        jdbcTemplate.update("INSERT INTO CATALOGO_ESTADOS (NOMBRE_TABLA, NOMBRE_CAMPO, CODIGO_ESTADO, DESCRIPCION, "
                + "ACTIVO, ORDEN) VALUES ('TAREA_CUIDADO', 'ESTADO', ?, 'Estado desactivado de prueba', 'N', 99)",
                codigo);
        long idEstadoDesactivado = idEstado("TAREA_CUIDADO", codigo);
        try {
            assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                    new SolicitudCambiarEstadoTarea(idEstadoDesactivado, null)))
                    .isInstanceOf(ExcepcionReglaNegocio.class)
                    .hasMessage("El estado indicado no corresponde a TAREA_CUIDADO.ESTADO");
        } finally {
            jdbcTemplate.update("DELETE FROM CATALOGO_ESTADOS WHERE ID_ESTADO = ?", idEstadoDesactivado);
        }
    }

    // ------------------------------------------------------------------
    // Permisos (regla 11 de la Fase 4)
    // ------------------------------------------------------------------

    @Test
    void colaboradorNoAsignado403() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        FixturaGeneracion.UsuarioAlto asignado = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo, "COLABORADOR",
                "ACTIVO");
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, asignado.idUsuario(), e.idCoordinador);
        FixturaGeneracion.UsuarioAlto otro = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo, "COLABORADOR",
                "ACTIVO");
        autenticar(otro.uid());
        long idEnCurso = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_EN_CURSO);

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEnCurso, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo el responsable asignado o el coordinador puede actualizar esta tarea");
    }

    @Test
    void colaboradorAsignadoPuede() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        FixturaGeneracion.UsuarioAlto asignado = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo, "COLABORADOR",
                "ACTIVO");
        assertThat(asignado.idUsuario()).isGreaterThan(127);
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, asignado.idUsuario(), e.idCoordinador);
        autenticar(asignado.uid());
        long idEnCurso = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_EN_CURSO);

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEnCurso, null));

        assertThat(respuesta.idEstado()).isEqualTo(idEnCurso);
    }

    @Test
    void sinAsignacionCualquierIntegrantePuede() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);
        long idEnCurso = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_EN_CURSO);

        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idEnCurso, null));

        assertThat(respuesta.idEstado()).isEqualTo(idEnCurso);
    }

    // ------------------------------------------------------------------
    // Recurso de otro grupo
    // ------------------------------------------------------------------

    @Test
    void tareaDeOtroGrupo404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        long idTareaAjena = fixturaTareas.crearTareaManual(ajeno.idGrupo, ajeno.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        autenticar(propio.uid);
        long idEnCurso = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_EN_CURSO);

        assertThatThrownBy(() -> servicio.cambiarEstado(propio.idGrupo, idTareaAjena,
                new SolicitudCambiarEstadoTarea(idEnCurso, null)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("La tarea no existe");
    }

    // ------------------------------------------------------------------
    // Cancelación (regla 5 y regla 11 de la Fase 4)
    // ------------------------------------------------------------------

    @Test
    void cancelarTareaManualSoloCoordinador() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, colaborador.idUsuario(), e.idCoordinador);
        long idCancelada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_CANCELADA);

        autenticar(colaborador.uid());
        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCancelada, "No corresponde")))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo el coordinador puede cancelar tareas");

        autenticar(e.uid);
        RespuestaTarea respuesta = servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCancelada, "El paciente ya no requiere la cita"));

        assertThat(respuesta.idEstado()).isEqualTo(idCancelada);
        assertThat(respuesta.motivoCancelacion()).isEqualTo("El paciente ya no requiere la cita");

        String estadoAsignacion = jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO "
                        + "WHERE a.ID_TAREA = ? AND a.ID_USUARIO_ASIGNADO = ?",
                String.class, idTarea, colaborador.idUsuario());
        assertThat(estadoAsignacion).isEqualTo("CANCELADA");
    }

    @Test
    void cancelarTareaDeMedicamento422() {
        Escenario e = escenario();
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartan fixtura estado tareas");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        long idTarea = fixturaTareas.crearTareaMedicamento(e.idGrupo, e.idAdulto, idDetalle, horario(HOY, 8, 0),
                "PENDIENTE", FixturaTareas.claveUnica());
        long idCancelada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_CANCELADA);

        assertThatThrownBy(() -> servicio.cambiarEstado(e.idGrupo, idTarea,
                new SolicitudCambiarEstadoTarea(idCancelada, "No corresponde")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Las tareas de medicamentos se cancelan modificando el plan");
    }

    // ------------------------------------------------------------------
    // Concurrencia e historial
    // ------------------------------------------------------------------

    @Test
    void confirmacionesConcurrentes() throws InterruptedException {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idCompletada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_COMPLETADA);

        CyclicBarrier barrera = new CyclicBarrier(2);
        AtomicInteger exitosos = new AtomicInteger();
        AtomicInteger rechazados = new AtomicInteger();
        CountDownLatch terminaron = new CountDownLatch(2);

        Runnable intento = () -> confirmarConcurrente(e, idTarea, idCompletada, barrera, exitosos, rechazados,
                terminaron);
        new Thread(intento).start();
        new Thread(intento).start();
        boolean terminaronATiempo = terminaron.await(30, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(exitosos.get()).isEqualTo(1);
        assertThat(rechazados.get()).isEqualTo(1);

        Long cantidadHistorialCompletada = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM HISTORIAL_ESTADO_TAREA WHERE ID_TAREA = ? AND ID_ESTADO_NUEVO = ?",
                Long.class, idTarea, idCompletada);
        assertThat(cantidadHistorialCompletada).isEqualTo(1);
    }

    private void confirmarConcurrente(Escenario e, long idTarea, long idCompletada, CyclicBarrier barrera,
            AtomicInteger exitosos, AtomicInteger rechazados, CountDownLatch terminaron) {
        try {
            autenticar(e.uid);
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            servicio.cambiarEstado(e.idGrupo, idTarea, new SolicitudCambiarEstadoTarea(idCompletada, "Completada"));
            exitosos.incrementAndGet();
        } catch (ExcepcionReglaNegocio excepcion) {
            rechazados.incrementAndGet();
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    @Test
    void historialNoSeSobrescribe() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idPendiente = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idEnCurso = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_EN_CURSO);
        long idCompletada = idEstado("TAREA_CUIDADO", CodigosTareas.ESTADO_TAREA_COMPLETADA);

        servicio.cambiarEstado(e.idGrupo, idTarea, new SolicitudCambiarEstadoTarea(idEnCurso, null));
        servicio.cambiarEstado(e.idGrupo, idTarea, new SolicitudCambiarEstadoTarea(idCompletada, "Listo"));

        List<Map<String, Object>> historial = jdbcTemplate.queryForList(
                "SELECT ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO FROM HISTORIAL_ESTADO_TAREA WHERE ID_TAREA = ? "
                        + "ORDER BY ID_HISTORIAL",
                idTarea);

        assertThat(historial).hasSize(2);
        assertThat(((Number) historial.get(0).get("ID_ESTADO_ANTERIOR")).longValue()).isEqualTo(idPendiente);
        assertThat(((Number) historial.get(0).get("ID_ESTADO_NUEVO")).longValue()).isEqualTo(idEnCurso);
        assertThat(((Number) historial.get(1).get("ID_ESTADO_ANTERIOR")).longValue()).isEqualTo(idEnCurso);
        assertThat(((Number) historial.get(1).get("ID_ESTADO_NUEVO")).longValue()).isEqualTo(idCompletada);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenario() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);
        return new Escenario(uid, idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(String uid, long idCoordinador, long idGrupo, long idAdulto) {
    }

    private long tareaManual(Escenario e, String codigoEstadoInicial) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        if (!CodigosTareas.ESTADO_TAREA_PENDIENTE.equals(codigoEstadoInicial)) {
            fixturaTareas.cambiarEstadoTarea(idTarea, codigoEstadoInicial);
        }
        return idTarea;
    }

    private boolean exigeObservacion(String codigoEstado) {
        return CodigosTareas.ESTADO_TAREA_NO_REALIZADA.equals(codigoEstado)
                || CodigosTareas.ESTADO_TAREA_CANCELADA.equals(codigoEstado);
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private String descripcionEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT DESCRIPCION FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                String.class, tabla, codigo);
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
