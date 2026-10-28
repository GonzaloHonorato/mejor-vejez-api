package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.FixturaAlertas;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ExcepcionSolicitudInvalida;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba {@link ServicioInternoReportes} contra Oracle real (Testcontainers,
 * Fase 9, TSA-61). El reloj de negocio solo lo usan {@code reservarEnvio}/
 * {@code registrarResultado} (fijo en el lunes 2026-11-02 10:00 de
 * Santiago); {@code semana} es puro respecto del reloj (depende solo del
 * {@code LocalDate inicio} recibido), así que las pruebas del cambio de
 * horario usan directamente las fechas de calendario de 2026-08-31.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioInternoReportesTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00): usado por reservarEnvio/registrarResultado. */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();
    private static final LocalDate LUNES = LocalDate.of(2026, 11, 2);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioInternoReportes servicio;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void preparar() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    // ------------------------------------------------------------------
    // GET /api/interno/reportes/grupos
    // ------------------------------------------------------------------

    @Test
    void gruposSoloActivosConIntegrantesYCuentaActiva() {
        long idCoordinador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoActivo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupoActivo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idIntegranteInactivo = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(idGrupoActivo, idIntegranteInactivo, "COLABORADOR", "INACTIVO");

        long idUsuarioCuentaInactiva = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(idGrupoActivo, idUsuarioCuentaInactiva, "COLABORADOR", "ACTIVO");
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstado("USUARIO", "INACTIVO"), idUsuarioCuentaInactiva);

        long idOtroCoordinador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoInactivo = fixturaGrupos.crearGrupo(idOtroCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupoInactivo, idOtroCoordinador, "COORDINADOR", "ACTIVO");
        jdbcTemplate.update("UPDATE GRUPO_FAMILIAR SET ID_ESTADO = ? WHERE ID_GRUPO = ?",
                idEstado("GRUPO_FAMILIAR", "INACTIVO"), idGrupoInactivo);

        List<GrupoReporte> grupos = servicio.grupos();

        assertThat(grupos).extracting(GrupoReporte::idGrupo).doesNotContain(idGrupoInactivo);
        GrupoReporte grupo = grupos.stream().filter(g -> g.idGrupo() == idGrupoActivo).findFirst().orElseThrow();
        assertThat(grupo.destinatarios()).extracting(Destinatario::idUsuario)
                .containsExactly(idCoordinador)
                .doesNotContain(idIntegranteInactivo, idUsuarioCuentaInactiva);
        assertThat(grupo.destinatarios().get(0).correo()).isEqualTo(correo(idCoordinador));
    }

    // ------------------------------------------------------------------
    // GET /api/interno/reportes/grupos/{idGrupo}/semana
    // ------------------------------------------------------------------

    @Test
    void semanaEstadoAlCierreDesdeHistorial() {
        Escenario e = escenario();
        OffsetDateTime cierre = e.cierre();

        long idTareaFueraDePlazo = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idTareaFueraDePlazo, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        // Completada el lunes SIGUIENTE a las 07:00: después del cierre (00:00 ese lunes).
        fixturaTareas.crearHistorial(idTareaFueraDePlazo, null, idEstadoTarea(CodigosTareas.ESTADO_TAREA_COMPLETADA),
                e.idCoordinador(), null, cierre.plusHours(7));

        long idTareaCompletadaElSabado = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(2));
        fixturaTareas.cambiarEstadoTarea(idTareaCompletadaElSabado, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        OffsetDateTime sabado = e.inicioInstante().plusDays(5);
        fixturaTareas.crearHistorial(idTareaCompletadaElSabado, null,
                idEstadoTarea(CodigosTareas.ESTADO_TAREA_COMPLETADA), e.idCoordinador(), null, sabado);

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        TareaSemana fueraDePlazo = buscarTarea(datos, idTareaFueraDePlazo);
        assertThat(fueraDePlazo.codigoEstadoAlCierre()).isEqualTo(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        assertThat(fueraDePlazo.fechaConfirmacion()).isNull();
        assertThat(fueraDePlazo.confirmadoPor()).isNull();

        TareaSemana completadaElSabado = buscarTarea(datos, idTareaCompletadaElSabado);
        assertThat(completadaElSabado.codigoEstadoAlCierre()).isEqualTo(CodigosTareas.ESTADO_TAREA_COMPLETADA);
        assertThat(completadaElSabado.fechaConfirmacion()).isEqualTo(ZonaNegocio.enZona(sabado));
        assertThat(completadaElSabado.confirmadoPor().idUsuario()).isEqualTo(e.idCoordinador());
    }

    @Test
    void semanaHistorialEmpataPorIdHistorialDescendente() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));
        OffsetDateTime mismoInstante = e.inicioInstante().plusDays(1);
        fixturaTareas.crearHistorial(idTarea, null, idEstadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO),
                e.idCoordinador(), null, mismoInstante);
        fixturaTareas.crearHistorial(idTarea, null, idEstadoTarea(CodigosTareas.ESTADO_TAREA_COMPLETADA),
                e.idCoordinador(), null, mismoInstante);

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        TareaSemana tarea = buscarTarea(datos, idTarea);
        assertThat(tarea.codigoEstadoAlCierre()).isEqualTo(CodigosTareas.ESTADO_TAREA_COMPLETADA);
    }

    @Test
    void semanaResponsableAlCierre() {
        Escenario e = escenario();
        long idUsuarioA = usuarioDelGrupo(e.idGrupo());
        long idUsuarioB = usuarioDelGrupo(e.idGrupo());
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));

        OffsetDateTime miercoles = e.inicioInstante().plusDays(2);
        fixturaTareas.crearAsignacionConEstado(idTarea, e.idGrupo(), idUsuarioA, e.idCoordinador(), "REEMPLAZADA",
                e.inicioInstante(), miercoles, null);
        fixturaTareas.crearAsignacionConEstado(idTarea, e.idGrupo(), idUsuarioB, e.idCoordinador(), "ACTIVO",
                miercoles, null, null);

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        TareaSemana tarea = buscarTarea(datos, idTarea);
        assertThat(tarea.reasignada()).isTrue();
        assertThat(tarea.responsableAlCierre().idUsuario()).isEqualTo(idUsuarioB);
    }

    @Test
    void semanaResponsableAlCierreEnElLimiteExactoSigueSiendoElSaliente() {
        // La asignación de A termina EXACTAMENTE en el cierre (lunes siguiente 00:00): como el cierre es
        // exclusivo, A sigue siendo el responsable de todo el período, no queda sin responsable.
        Escenario e = escenario();
        long idUsuarioA = usuarioDelGrupo(e.idGrupo());
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));
        fixturaTareas.crearAsignacionConEstado(idTarea, e.idGrupo(), idUsuarioA, e.idCoordinador(), "REEMPLAZADA",
                e.inicioInstante(), e.cierre(), null);

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        TareaSemana tarea = buscarTarea(datos, idTarea);
        assertThat(tarea.responsableAlCierre()).isNotNull();
        assertThat(tarea.responsableAlCierre().idUsuario()).isEqualTo(idUsuarioA);
    }

    @Test
    void semanaSinResponsableEsNull() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        TareaSemana tarea = buscarTarea(datos, idTarea);
        assertThat(tarea.responsableAlCierre()).isNull();
        assertThat(tarea.reasignada()).isFalse();
    }

    @Test
    void semanaIncluyeFinDeSemana() {
        Escenario e = escenario();
        long idTareaDomingo = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusDays(6).plusHours(22));
        long idTareaLunesSiguiente = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.cierre());

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        assertThat(datos.tareas()).extracting(TareaSemana::idTarea).contains(idTareaDomingo)
                .doesNotContain(idTareaLunesSiguiente);
    }

    @Test
    void semanaConCambioDeHorarioCubreSieteDias() {
        LocalDate inicio = LocalDate.of(2026, 8, 31); // lunes
        Escenario e = escenario();
        OffsetDateTime cierre = OffsetDateTime.parse("2026-09-07T00:00:00-03:00");
        long idTareaAntesDelCierre = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, cierre.minusSeconds(1));
        // Si el cierre se calculara sumando 168 horas en vez de 7 días de calendario, esta tarea
        // (exactamente en el cierre real) quedaría adentro por error: la hora perdida del cambio
        // de horario corre el cierre naive una hora más tarde.
        long idTareaEnElCierre = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, cierre);

        DatosSemana datos = servicio.semana(e.idGrupo(), inicio);

        assertThat(datos.fin()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(datos.tareas()).extracting(TareaSemana::idTarea).contains(idTareaAntesDelCierre)
                .doesNotContain(idTareaEnElCierre);
    }

    @Test
    void semanaSoloDelGrupo() {
        Escenario a = escenario();
        Escenario b = escenario();
        long idTareaA = fixturaTareas.crearTareaManual(a.idGrupo(), a.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, a.inicioInstante().plusHours(1));
        long idTareaB = fixturaTareas.crearTareaManual(b.idGrupo(), b.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, b.inicioInstante().plusHours(1));

        long idRelevoB = crearTraspaso(b.idGrupo(), b.idAdulto(), b.idCoordinador(), usuarioDelGrupo(b.idGrupo()),
                b.inicioInstante().plusDays(1), "COMPLETADA");
        crearDetalleTraspaso(idRelevoB, "TAREA", idTareaB);
        long idAlertaB = fixturaAlertas.crearAlertaDeTarea(b.idGrupo(), b.idAdulto(), idTareaB,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarFechaDeteccion(idAlertaB, b.inicioInstante().plusDays(1));

        DatosSemana datos = servicio.semana(a.idGrupo(), a.inicio());

        assertThat(datos.tareas()).extracting(TareaSemana::idTarea).containsExactly(idTareaA)
                .doesNotContain(idTareaB);
        assertThat(datos.adultos()).extracting(AdultoSemana::idAdulto).doesNotContain(b.idAdulto());
        assertThat(datos.relevos()).extracting(RelevoSemana::idRelevo).doesNotContain(idRelevoB);
        assertThat(datos.alertas()).extracting(AlertaSemana::idAlerta).doesNotContain(idAlertaB);
    }

    @Test
    void semanaIncluyeRelevosYAlertasDelPeriodo() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.inicioInstante().plusHours(1));

        long idRelevo = crearTraspaso(e.idGrupo(), e.idAdulto(), e.idCoordinador(), usuarioDelGrupo(e.idGrupo()),
                e.inicioInstante().plusDays(1), "COMPLETADA");
        crearDetalleTraspaso(idRelevo, "TAREA", idTarea);
        crearDetalleTraspaso(idRelevo, "ALERTA", idTarea);
        crearDetalleTraspaso(idRelevo, "ALERTA", idTarea);

        long idAlertaAbierta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarFechaDeteccion(idAlertaAbierta, e.inicioInstante().plusDays(1));

        long idAlertaCerradaAntesDelCierre = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarFechaDeteccion(idAlertaCerradaAntesDelCierre, e.inicioInstante().plusDays(1));
        fixturaAlertas.cerrarAlertaConFecha(idAlertaCerradaAntesDelCierre, e.inicioInstante().plusDays(2));

        // Cerrada DESPUÉS del cierre: su estado al cierre es ABIERTA, aunque hoy ya esté CERRADA.
        OffsetDateTime despuesDelCierre = e.cierre().plusDays(1);
        long idAlertaCerradaDespuesDelCierre = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarFechaDeteccion(idAlertaCerradaDespuesDelCierre, e.inicioInstante().plusDays(1));
        fixturaAlertas.cerrarAlertaConFecha(idAlertaCerradaDespuesDelCierre, despuesDelCierre);

        // Relevo y alerta del lunes SIGUIENTE a las 00:00 (exactamente el cierre): fuera del período.
        long idRelevoFueraDePeriodo = crearTraspaso(e.idGrupo(), e.idAdulto(), e.idCoordinador(),
                usuarioDelGrupo(e.idGrupo()), e.cierre(), "COMPLETADA");
        long idAlertaFueraDePeriodo = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarFechaDeteccion(idAlertaFueraDePeriodo, e.cierre());

        DatosSemana datos = servicio.semana(e.idGrupo(), e.inicio());

        RelevoSemana relevo = datos.relevos().stream().filter(r -> r.idRelevo() == idRelevo).findFirst().orElseThrow();
        assertThat(relevo.totalTareas()).isEqualTo(1);
        assertThat(relevo.totalAlertas()).isEqualTo(2);
        assertThat(relevo.codigoEstado()).isEqualTo("COMPLETADA");

        AlertaSemana abierta = datos.alertas().stream().filter(al -> al.idAlerta() == idAlertaAbierta).findFirst()
                .orElseThrow();
        assertThat(abierta.codigoEstadoAlCierre()).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);

        AlertaSemana cerrada = datos.alertas().stream().filter(al -> al.idAlerta() == idAlertaCerradaAntesDelCierre)
                .findFirst().orElseThrow();
        assertThat(cerrada.codigoEstadoAlCierre()).isEqualTo(CodigosAlertas.ESTADO_ALERTA_CERRADA);

        AlertaSemana cerradaDespues = datos.alertas().stream()
                .filter(al -> al.idAlerta() == idAlertaCerradaDespuesDelCierre).findFirst().orElseThrow();
        assertThat(cerradaDespues.codigoEstadoAlCierre()).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        assertThat(cerradaDespues.fechaRevision()).isEqualTo(ZonaNegocio.enZona(despuesDelCierre));

        assertThat(datos.relevos()).extracting(RelevoSemana::idRelevo).doesNotContain(idRelevoFueraDePeriodo);
        assertThat(datos.alertas()).extracting(AlertaSemana::idAlerta).doesNotContain(idAlertaFueraDePeriodo);
    }

    @Test
    void semanaInicioNoLunesLanzaSolicitudInvalida() {
        Escenario e = escenario();
        assertThatThrownBy(() -> servicio.semana(e.idGrupo(), e.inicio().plusDays(1)))
                .isInstanceOf(ExcepcionSolicitudInvalida.class);
    }

    @Test
    void semanaDeGrupoInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.semana(999_999_999L, LUNES)).isInstanceOf(ExcepcionNoEncontrado.class);
    }

    // ------------------------------------------------------------------
    // POST /api/interno/reportes/envios
    // ------------------------------------------------------------------

    @Test
    void reservaEnvioCreaConFotoDeDestinatarios() {
        Escenario e = escenario();
        long idOtroDestinatario = usuarioDelGrupo(e.idGrupo());

        RespuestaReservaEnvio respuesta = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));

        assertThat(respuesta.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
        assertThat(respuesta.intentos()).isZero();
        assertThat(respuesta.pendientes()).extracting(Destinatario::idUsuario)
                .containsExactlyInAnyOrder(e.idCoordinador(), idOtroDestinatario);
        Integer filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ENVIO_REPORTE_DESTINATARIO WHERE ID_ENVIO = ?", Integer.class,
                respuesta.idEnvio());
        assertThat(filas).isEqualTo(2);
    }

    @Test
    void reservaEnvioSinDestinatariosNaceEnviado() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador); // sin integrantes: ni el creador se agrega.

        RespuestaReservaEnvio respuesta = servicio.reservarEnvio(new SolicitudReservarEnvio(idGrupo, LUNES));

        assertThat(respuesta.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        assertThat(respuesta.intentos()).isZero();
        assertThat(respuesta.pendientes()).isEmpty();
        String detalle = jdbcTemplate.queryForObject("SELECT DETALLE_ERROR FROM ENVIO_REPORTE WHERE ID_ENVIO = ?",
                String.class, respuesta.idEnvio());
        assertThat(detalle).isEqualTo("Sin destinatarios activos");
        Integer filasDestinatario = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ENVIO_REPORTE_DESTINATARIO WHERE ID_ENVIO = ?", Integer.class,
                respuesta.idEnvio());
        assertThat(filasDestinatario).isZero();
    }

    @Test
    void reservaEnvioEsIdempotente() {
        Escenario e = escenario();

        RespuestaReservaEnvio primera = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        RespuestaReservaEnvio segunda = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));

        assertThat(segunda.idEnvio()).isEqualTo(primera.idEnvio());
        Integer filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ENVIO_REPORTE WHERE ID_GRUPO = ? AND INICIO_PERIODO = ?", Integer.class,
                e.idGrupo(), Date.valueOf(LUNES));
        assertThat(filas).isEqualTo(1);
    }

    @Test
    void reservaEnvioDeGrupoInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.reservarEnvio(new SolicitudReservarEnvio(999_999_999L, LUNES)))
                .isInstanceOf(ExcepcionNoEncontrado.class);
    }

    @Test
    void reservaEnvioInicioNoLunesLanzaSolicitudInvalida() {
        Escenario e = escenario();
        assertThatThrownBy(
                () -> servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES.plusDays(1))))
                .isInstanceOf(ExcepcionSolicitudInvalida.class);
    }

    @Test
    void reservaEnvioConInicioNuloLanzaSolicitudInvalida() {
        Escenario e = escenario();
        assertThatThrownBy(() -> servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), null)))
                .isInstanceOf(ExcepcionSolicitudInvalida.class);
    }

    @Test
    void semanaConInicioNuloLanzaSolicitudInvalida() {
        Escenario e = escenario();
        assertThatThrownBy(() -> servicio.semana(e.idGrupo(), null)).isInstanceOf(ExcepcionSolicitudInvalida.class);
    }

    @Test
    void reservasConcurrentesDelMismoGrupoYPeriodoDanElMismoIdEnvio() throws InterruptedException {
        Escenario e = escenario();
        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<RespuestaReservaEnvio> resultados = new CopyOnWriteArrayList<>();
        List<Throwable> fallas = new CopyOnWriteArrayList<>();

        new Thread(() -> reservarConcurrente(e.idGrupo(), barrera, resultados, fallas, terminaron)).start();
        new Thread(() -> reservarConcurrente(e.idGrupo(), barrera, resultados, fallas, terminaron)).start();
        boolean aTiempo = terminaron.await(60, TimeUnit.SECONDS);

        assertThat(aTiempo).isTrue();
        assertThat(fallas).isEmpty();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0).idEnvio()).isEqualTo(resultados.get(1).idEnvio());
        Integer filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ENVIO_REPORTE WHERE ID_GRUPO = ? AND INICIO_PERIODO = ?", Integer.class,
                e.idGrupo(), Date.valueOf(LUNES));
        assertThat(filas).isEqualTo(1);
    }

    private void reservarConcurrente(long idGrupo, CyclicBarrier barrera, List<RespuestaReservaEnvio> resultados,
            List<Throwable> fallas, CountDownLatch terminaron) {
        try {
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            RespuestaReservaEnvio respuesta = new TransactionTemplate(gestorTransacciones)
                    .execute(estado -> servicio.reservarEnvio(new SolicitudReservarEnvio(idGrupo, LUNES)));
            resultados.add(respuesta);
        } catch (Throwable error) {
            fallas.add(error);
        } finally {
            terminaron.countDown();
        }
    }

    // ------------------------------------------------------------------
    // PUT /api/interno/reportes/envios/{idEnvio}/resultado
    // ------------------------------------------------------------------

    @Test
    void envioEnviadoNoDevuelvePendientes() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        List<Long> idsDestinatario = idsDestinatariosDelEnvio(creado.idEnvio());

        RespuestaResultadoEnvio resultado = servicio.registrarResultado(creado.idEnvio(),
                new SolicitudResultadoEnvio(idsDestinatario.stream()
                        .map(id -> new ResultadoDestinatario(id, true, null)).toList()));
        assertThat(resultado.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_ENVIADO);

        RespuestaReservaEnvio reconsulta = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        assertThat(reconsulta.idEnvio()).isEqualTo(creado.idEnvio());
        assertThat(reconsulta.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        assertThat(reconsulta.pendientes()).isEmpty();
    }

    @Test
    void resultadoParcialSoloReintentaPendientes() {
        Escenario e = escenario();
        List<Long> idsDestinatario = new ArrayList<>();
        idsDestinatario.add(e.idCoordinador());
        for (int i = 0; i < 4; i++) {
            idsDestinatario.add(usuarioDelGrupo(e.idGrupo()));
        }
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        assertThat(creado.pendientes()).hasSize(5);

        List<Long> fallidos = idsDestinatario.subList(0, 2);
        List<Long> enviados = idsDestinatario.subList(2, 5);
        List<ResultadoDestinatario> resultados = new ArrayList<>();
        enviados.forEach(id -> resultados.add(new ResultadoDestinatario(id, true, null)));
        fallidos.forEach(id -> resultados.add(new ResultadoDestinatario(id, false, "SMTP no responde")));

        RespuestaResultadoEnvio resultado =
                servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(resultados));
        assertThat(resultado.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_PENDIENTE);

        RespuestaReservaEnvio reconsulta = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        assertThat(reconsulta.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
        assertThat(reconsulta.intentos()).isEqualTo(1);
        assertThat(reconsulta.pendientes()).extracting(Destinatario::idUsuario)
                .containsExactlyInAnyOrderElementsOf(fallidos);
    }

    @Test
    void tresIntentosConPendientesQuedaFallido() {
        Escenario e = escenario();
        long idOtro = usuarioDelGrupo(e.idGrupo());
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));

        for (int intento = 1; intento <= 3; intento++) {
            RespuestaResultadoEnvio resultado = servicio.registrarResultado(creado.idEnvio(),
                    new SolicitudResultadoEnvio(List.of(
                            new ResultadoDestinatario(e.idCoordinador(), true, null),
                            new ResultadoDestinatario(idOtro, false, "SMTP no responde"))));
            if (intento < 3) {
                assertThat(resultado.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
            } else {
                assertThat(resultado.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_FALLIDO);
            }
        }

        RespuestaReservaEnvio reconsulta = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        assertThat(reconsulta.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_FALLIDO);
        assertThat(reconsulta.intentos()).isEqualTo(3);
        assertThat(reconsulta.pendientes()).isEmpty();
    }

    @Test
    void resultadoSobreDestinatarioYaEnviadoSeIgnora() {
        Escenario e = escenario();
        long idOtro = usuarioDelGrupo(e.idGrupo());
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(List.of(
                new ResultadoDestinatario(e.idCoordinador(), true, null),
                new ResultadoDestinatario(idOtro, false, "SMTP no responde"))));

        // Un segundo PUT intenta "revertir" al coordinador (ya ENVIADO) a FALLIDO, y confirma al otro
        // (antes FALLIDO) como ENVIADO: el reintento sí se aplica, la reversión se ignora.
        RespuestaResultadoEnvio resultado = servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(
                List.of(new ResultadoDestinatario(e.idCoordinador(), false, "No debería aplicarse"),
                        new ResultadoDestinatario(idOtro, true, null))));

        assertThat(resultado.estado()).isEqualTo(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        Map<String, Object> filaCoordinador = jdbcTemplate.queryForMap(
                "SELECT ce.CODIGO_ESTADO, d.DETALLE_ERROR FROM ENVIO_REPORTE_DESTINATARIO d "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = d.ID_ESTADO "
                        + "WHERE d.ID_ENVIO = ? AND d.ID_USUARIO = ?",
                creado.idEnvio(), e.idCoordinador());
        assertThat(filaCoordinador.get("CODIGO_ESTADO")).isEqualTo(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        assertThat(filaCoordinador.get("DETALLE_ERROR")).isNull();
    }

    @Test
    void detalleErrorDelDestinatarioSeRecortaA500Bytes() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        String detalleLargo = "x".repeat(600);

        servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(
                List.of(new ResultadoDestinatario(e.idCoordinador(), false, detalleLargo))));

        String detalleGuardado = jdbcTemplate.queryForObject(
                "SELECT DETALLE_ERROR FROM ENVIO_REPORTE_DESTINATARIO WHERE ID_ENVIO = ? AND ID_USUARIO = ?",
                String.class, creado.idEnvio(), e.idCoordinador());
        assertThat(detalleGuardado).hasSize(500);
    }

    @Test
    void putSobreEnvioCerradoDa409() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        List<Long> idsDestinatario = idsDestinatariosDelEnvio(creado.idEnvio());
        servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(
                idsDestinatario.stream().map(id -> new ResultadoDestinatario(id, true, null)).toList()));

        assertThatThrownBy(() -> servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(
                List.of(new ResultadoDestinatario(idsDestinatario.get(0), true, null)))))
                .isInstanceOf(ExcepcionConflicto.class);
    }

    @Test
    void putConIdUsuarioAjenoDa422() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        long idUsuarioAjeno = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);

        assertThatThrownBy(() -> servicio.registrarResultado(creado.idEnvio(),
                new SolicitudResultadoEnvio(List.of(new ResultadoDestinatario(idUsuarioAjeno, true, null)))))
                .isInstanceOf(ExcepcionReglaNegocio.class);
    }

    @Test
    void putConListaVaciaDa422() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));

        assertThatThrownBy(() -> servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(List.of())))
                .isInstanceOf(ExcepcionReglaNegocio.class);
    }

    @Test
    void putConElementoNuloEnResultadosLanzaSolicitudInvalida() {
        Escenario e = escenario();
        RespuestaReservaEnvio creado = servicio.reservarEnvio(new SolicitudReservarEnvio(e.idGrupo(), LUNES));
        List<ResultadoDestinatario> resultados = new ArrayList<>();
        resultados.add(new ResultadoDestinatario(e.idCoordinador(), true, null));
        resultados.add(null);

        assertThatThrownBy(() -> servicio.registrarResultado(creado.idEnvio(), new SolicitudResultadoEnvio(resultados)))
                .isInstanceOf(ExcepcionSolicitudInvalida.class);
    }

    @Test
    void putDeEnvioInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.registrarResultado(999_999_999L,
                new SolicitudResultadoEnvio(List.of(new ResultadoDestinatario(1L, true, null)))))
                .isInstanceOf(ExcepcionNoEncontrado.class);
    }

    // ------------------------------------------------------------------
    // Escenario y ayudantes
    // ------------------------------------------------------------------

    /** Grupo ACTIVO con un coordinador ACTIVO y un adulto ACTIVO, lunes 2026-11-02. */
    private Escenario escenario() {
        long idCoordinador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        LocalDate inicio = LUNES;
        OffsetDateTime inicioInstante = inicio.atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime cierre = inicio.plusDays(7).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        return new Escenario(idGrupo, idCoordinador, idAdulto, inicio, inicioInstante, cierre);
    }

    private record Escenario(long idGrupo, long idCoordinador, long idAdulto, LocalDate inicio,
            OffsetDateTime inicioInstante, OffsetDateTime cierre) {
    }

    private long usuarioDelGrupo(long idGrupo) {
        long idUsuario = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuario, "COLABORADOR", "ACTIVO");
        return idUsuario;
    }

    private TareaSemana buscarTarea(DatosSemana datos, long idTarea) {
        return datos.tareas().stream().filter(t -> t.idTarea() == idTarea).findFirst().orElseThrow();
    }

    private List<Long> idsDestinatariosDelEnvio(long idEnvio) {
        return jdbcTemplate.queryForList("SELECT ID_USUARIO FROM ENVIO_REPORTE_DESTINATARIO WHERE ID_ENVIO = ?",
                Long.class, idEnvio);
    }

    private long crearTraspaso(long idGrupo, long idAdulto, long idSaliente, long idEntrante,
            OffsetDateTime fechaTraspaso, String codigoEstado) {
        jdbcTemplate.update(
                "INSERT INTO TRASPASO_FAMILIAR (ID_GRUPO, ID_ADULTO_MAYOR, ID_USUARIO_SALIENTE, "
                        + "ID_USUARIO_ENTRANTE, FECHA_TRASPASO, ID_USUARIO_CREADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                idGrupo, idAdulto, idSaliente, idEntrante, fechaTraspaso, idSaliente,
                idEstado("TRASPASO_FAMILIAR", codigoEstado));
        return jdbcTemplate.queryForObject("SELECT MAX(ID_TRASPASO) FROM TRASPASO_FAMILIAR WHERE ID_GRUPO = ?",
                Long.class, idGrupo);
    }

    private void crearDetalleTraspaso(long idTraspaso, String tipoElemento, long idReferencia) {
        jdbcTemplate.update(
                "INSERT INTO DETALLE_TRASPASO (ID_TRASPASO, TIPO_ELEMENTO, ID_REFERENCIA) VALUES (?, ?, ?)",
                idTraspaso, tipoElemento, idReferencia);
    }

    private String correo(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT EMAIL FROM USUARIO WHERE ID_USUARIO = ?", String.class,
                idUsuario);
    }

    private long idEstadoTarea(String codigo) {
        return idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, codigo);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }
}
