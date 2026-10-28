package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

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
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.notificaciones.CodigosNotificaciones;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba la reserva y el resultado de los intentos de envío (Fase 9,
 * TSA-61) contra Oracle real (Testcontainers), con el reloj fijo en el
 * lunes 2026-11-02 10:00 de Santiago (mismo instante que {@code
 * ServicioInternoAlertasTest}, en un rango de ids de usuario propio).
 * {@link ServicioReservaIntentos} nunca envía nada: solo reserva en {@code
 * PENDIENTE}, sin depender de ningún componente de envío de correo o
 * WhatsApp (ver también {@code correoSigueOperativoAunSinNingunComponenteDeEnvioEnElCore}).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioReservaIntentosTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00). */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** Ids de usuario explícitos > 127, en un rango propio. */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(985_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioReservaIntentos servicio;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaAlertas fixturaAlertas;
    private FixturaPlanes fixturaPlanes;

    @BeforeEach
    void preparar() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    // ------------------------------------------------------------------
    // POST .../intentos/reservar
    // ------------------------------------------------------------------

    @Test
    void reservaCreaPendientePorParElegible() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.alerta().idAlerta()).isEqualTo(e.idAlerta);
        assertThat(respuesta.alerta().idGrupo()).isEqualTo(e.idGrupo);
        assertThat(respuesta.alerta().tipoAlerta()).isEqualTo(CodigosAlertas.TIPO_ATRASO_TAREA);
        assertThat(respuesta.alerta().mensaje()).isNotBlank();

        assertThat(respuesta.intentos()).extracting(IntentoReservado::idUsuario)
                .containsExactly(e.idCoordinador, idResponsable);
        assertThat(respuesta.intentos()).extracting(IntentoReservado::nombre)
                .containsExactly(nombre(e.idCoordinador), nombre(idResponsable));
        assertThat(respuesta.intentos()).extracting(IntentoReservado::destino)
                .containsExactly(correo(e.idCoordinador), correo(idResponsable));
        for (IntentoReservado intento : respuesta.intentos()) {
            assertThat(intento.canal()).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
            assertThat(intento.numeroIntento()).isEqualTo(1);
            assertThat(intento.idIntento()).isNotNull();
            assertThat(fixturaAlertas.estadoIntento(intento.idIntento())).isEqualTo(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        }
        assertThat(fixturaAlertas.estadoAlerta(e.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void alertaCerradaNoReserva() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.cambiarEstadoAlerta(e.idAlerta, CodigosAlertas.ESTADO_ALERTA_CERRADA);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.alerta().idAlerta()).isEqualTo(e.idAlerta);
        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
    }

    @Test
    void reservaRespetaMaximoDeIntentos() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 2,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 3,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(3);
    }

    @Test
    void reservaRespetaMaximoDeIntentosConPendienteHuerfanoExpirado() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 2,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        long idIntentoHuerfano = fixturaAlertas.crearIntentoConFecha(e.idAlerta, idResponsable,
                CodigosNotificaciones.CANAL_CORREO, 3, CodigosAlertas.ESTADO_ENVIO_PENDIENTE,
                AHORA.withHour(9).withMinute(45));

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(fixturaAlertas.estadoIntento(idIntentoHuerfano)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(3);
    }

    @Test
    void reservaNoRepiteParYaEnviado() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(1);
    }

    @Test
    void pendienteHuerfanoDeMasDe10MinutosPasaAFallido() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        long idIntentoHuerfano = fixturaAlertas.crearIntentoConFecha(e.idAlerta, idResponsable,
                CodigosNotificaciones.CANAL_CORREO, 1, CodigosAlertas.ESTADO_ENVIO_PENDIENTE,
                AHORA.withHour(9).withMinute(45));

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(fixturaAlertas.estadoIntento(idIntentoHuerfano)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        assertThat(detalleError(idIntentoHuerfano)).isEqualTo(ServicioReservaIntentos.MENSAJE_SIN_CONFIRMACION);
        assertThat(respuesta.intentos()).hasSize(1);
        IntentoReservado nuevo = respuesta.intentos().get(0);
        assertThat(nuevo.idUsuario()).isEqualTo(idResponsable);
        assertThat(nuevo.numeroIntento()).isEqualTo(2);
        assertThat(fixturaAlertas.estadoIntento(nuevo.idIntento())).isEqualTo(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
    }

    @Test
    void pendienteRecienteNoSeDuplica() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        long idIntentoVigente = fixturaAlertas.crearIntentoConFecha(e.idAlerta, idResponsable,
                CodigosNotificaciones.CANAL_CORREO, 1, CodigosAlertas.ESTADO_ENVIO_PENDIENTE,
                AHORA.withHour(9).withMinute(55));

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.estadoIntento(idIntentoVigente)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(1);
    }

    @Test
    void origenInactivoNoReserva() {
        Escenario e = escenario(true);
        fixturaTareas.cambiarEstadoTarea(e.idTarea, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).isEmpty();
        assertThat(respuesta.alerta().idAlerta()).isEqualTo(e.idAlerta);
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
        assertThat(fixturaAlertas.estadoAlerta(e.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void reservarIntentosDeAlertaInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.reservarIntentos(999_999_999L))
                .isInstanceOf(ExcepcionNoEncontrado.class);
    }

    /**
     * El Core ya no tiene ningún componente que envíe correos (el envío lo
     * hace el microservicio de Alertas): el canal CORREO debe seguir
     * operativo igual, porque la reserva de intentos no envía nada, solo
     * deja el par en PENDIENTE para que Alertas lo envíe y confirme.
     */
    @Test
    void correoSigueOperativoAunSinNingunComponenteDeEnvioEnElCore() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).isNotEmpty();
        assertThat(respuesta.intentos()).extracting(IntentoReservado::canal)
                .containsOnly(CodigosNotificaciones.CANAL_CORREO);
    }

    // ------------------------------------------------------------------
    // PUT /api/interno/intentos/{idIntento}
    // ------------------------------------------------------------------

    @Test
    void resultadoPasaDePendienteAFinal() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        long idIntento = fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO,
                1, CodigosAlertas.ESTADO_ENVIO_PENDIENTE);

        servicio.registrarResultado(idIntento, new SolicitudResultadoIntento(CodigosAlertas.ESTADO_ENVIO_ENVIADO,
                null, "<ref-123@mejorvejez.test>"));

        assertThat(fixturaAlertas.estadoIntento(idIntento)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        assertThat(referenciaProveedor(idIntento)).isEqualTo("<ref-123@mejorvejez.test>");
    }

    @Test
    void resultadoRepetidoMismoEstadoDa204() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        long idIntento = fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO,
                1, CodigosAlertas.ESTADO_ENVIO_FALLIDO);

        assertThatCode(() -> servicio.registrarResultado(idIntento,
                new SolicitudResultadoIntento(CodigosAlertas.ESTADO_ENVIO_FALLIDO, "Servidor SMTP no responde",
                        null))).doesNotThrowAnyException();

        assertThat(fixturaAlertas.estadoIntento(idIntento)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
    }

    @Test
    void resultadoSobreOtroFinalDa409() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        long idIntento = fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO,
                1, CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        assertThatThrownBy(() -> servicio.registrarResultado(idIntento,
                new SolicitudResultadoIntento(CodigosAlertas.ESTADO_ENVIO_FALLIDO, "Error", null)))
                .isInstanceOf(ExcepcionConflicto.class);

        assertThat(fixturaAlertas.estadoIntento(idIntento)).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
    }

    @Test
    void resultadoDeIntentoInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.registrarResultado(999_999_999L,
                new SolicitudResultadoIntento(CodigosAlertas.ESTADO_ENVIO_ENVIADO, null, null)))
                .isInstanceOf(ExcepcionNoEncontrado.class);
    }

    @Test
    void detalleErrorSeRecortaA500Bytes() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        long idIntento = fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO,
                1, CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        String detalleLargo = "x".repeat(600);

        servicio.registrarResultado(idIntento,
                new SolicitudResultadoIntento(CodigosAlertas.ESTADO_ENVIO_FALLIDO, detalleLargo, null));

        String detalleGuardado = detalleError(idIntento);
        assertThat(detalleGuardado).hasSize(500);
        assertThat(detalleGuardado.getBytes(StandardCharsets.UTF_8).length).isEqualTo(500);
    }

    // ------------------------------------------------------------------
    // GET .../envios-pendientes
    // ------------------------------------------------------------------

    @Test
    void enviosPendientesListaAlertasConParesPorIntentar() {
        OffsetDateTime desde = AHORA.minusHours(2);

        Escenario nueva = escenario(false); // sin ningún intento todavía.
        fixturaAlertas.cambiarFechaDeteccion(nueva.idAlerta, AHORA.minusHours(1));
        long idResponsableParcial = usuarioAlto(nueva.idGrupo);
        fixturaTareas.crearAsignacion(nueva.idTarea, nueva.idGrupo, idResponsableParcial, nueva.idCoordinador);

        Escenario conFallidoBajoElMaximo = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(conFallidoBajoElMaximo.idAlerta, AHORA.minusHours(1));
        long idResponsableBajoMaximo = usuarioAlto(conFallidoBajoElMaximo.idGrupo);
        fixturaTareas.crearAsignacion(conFallidoBajoElMaximo.idTarea, conFallidoBajoElMaximo.idGrupo,
                idResponsableBajoMaximo, conFallidoBajoElMaximo.idCoordinador);
        fixturaAlertas.crearIntento(conFallidoBajoElMaximo.idAlerta, idResponsableBajoMaximo,
                CodigosNotificaciones.CANAL_CORREO, 1, CodigosAlertas.ESTADO_ENVIO_FALLIDO);

        Escenario agotada = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(agotada.idAlerta, AHORA.minusHours(1));
        long idResponsableAgotado = usuarioAlto(agotada.idGrupo);
        fixturaTareas.crearAsignacion(agotada.idTarea, agotada.idGrupo, idResponsableAgotado, agotada.idCoordinador);
        fixturaAlertas.crearIntento(agotada.idAlerta, idResponsableAgotado, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(agotada.idAlerta, idResponsableAgotado, CodigosNotificaciones.CANAL_CORREO, 2,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(agotada.idAlerta, idResponsableAgotado, CodigosNotificaciones.CANAL_CORREO, 3,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);

        Escenario enviada = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(enviada.idAlerta, AHORA.minusHours(1));
        long idResponsableEnviado = usuarioAlto(enviada.idGrupo);
        fixturaTareas.crearAsignacion(enviada.idTarea, enviada.idGrupo, idResponsableEnviado, enviada.idCoordinador);
        fixturaAlertas.crearIntento(enviada.idAlerta, idResponsableEnviado, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        Escenario cerrada = escenario(false); // de otro modo sería elegible (sin intentos).
        fixturaAlertas.cambiarFechaDeteccion(cerrada.idAlerta, AHORA.minusHours(1));
        fixturaAlertas.cambiarEstadoAlerta(cerrada.idAlerta, CodigosAlertas.ESTADO_ALERTA_CERRADA);

        Escenario detectadaAntesDeDesde = escenario(false); // de otro modo sería elegible (sin intentos).
        fixturaAlertas.cambiarFechaDeteccion(detectadaAntesDeDesde.idAlerta, AHORA.minusDays(10));

        List<Long> ids = servicio.enviosPendientes(desde);

        assertThat(ids).contains(nueva.idAlerta, conFallidoBajoElMaximo.idAlerta)
                .doesNotContain(agotada.idAlerta, enviada.idAlerta, cerrada.idAlerta,
                        detectadaAntesDeDesde.idAlerta);
    }

    @Test
    void enviosPendientesIncluyeDestinatarioNuevoTrasReasignacion() {
        OffsetDateTime desde = AHORA.minusHours(2);
        Escenario e = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(e.idAlerta, AHORA.minusHours(1));
        long idResponsableAnterior = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsableAnterior, e.idCoordinador);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsableAnterior, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        // El responsable anterior deja de estar activo y entra uno nuevo, sin ningún intento previo: la
        // alerta sigue ABIERTA (la detección no la recrea, es idempotente por clave) pero ahora tiene un
        // destinatario que nunca recibió nada.
        jdbcTemplate.update("UPDATE ASIGNACION_TAREA SET ID_ESTADO = ? WHERE ID_TAREA = ? AND ID_USUARIO_ASIGNADO = ?",
                idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CodigosTareas.CAMPO_ESTADO,
                        CodigosTareas.ESTADO_ASIGNACION_REEMPLAZADA),
                e.idTarea, idResponsableAnterior);
        long idResponsableNuevo = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsableNuevo, e.idCoordinador);

        List<Long> ids = servicio.enviosPendientes(desde);

        assertThat(ids).contains(e.idAlerta);
    }

    /**
     * Recuperación de huérfanos (al menos una vez): un {@code PENDIENTE} de
     * más de {@value ServicioReservaIntentos#VENTANA_CONFIRMACION_MINUTOS}
     * minutos no bloquea el par, así que la alerta vuelve a aparecer en
     * {@code envios-pendientes}.
     */
    @Test
    void enviosPendientesIncluyeAlertaConUnPendienteDeMasDeDiezMinutos() {
        OffsetDateTime desde = AHORA.minusHours(2);
        Escenario e = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(e.idAlerta, AHORA.minusHours(1));
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntentoConFecha(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_PENDIENTE, AHORA.minusMinutes(11));

        List<Long> ids = servicio.enviosPendientes(desde);

        assertThat(ids).contains(e.idAlerta);
    }

    /** Mismo caso que el anterior, pero con un {@code PENDIENTE} reciente: sigue vigente y bloquea. */
    @Test
    void enviosPendientesExcluyeAlertaConUnPendienteReciente() {
        OffsetDateTime desde = AHORA.minusHours(2);
        Escenario e = escenario(false);
        fixturaAlertas.cambiarFechaDeteccion(e.idAlerta, AHORA.minusHours(1));
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntentoConFecha(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_PENDIENTE, AHORA.minusMinutes(5));

        List<Long> ids = servicio.enviosPendientes(desde);

        assertThat(ids).doesNotContain(e.idAlerta);
    }

    // ------------------------------------------------------------------
    // Destinatarios y canales (reglas 7 y 8 de la Fase 5), adaptadas de
    // "notificar" a "reservar" (el envío ya no lo hace el Core, pero sigue
    // siendo quien decide a quién le toca un intento).
    // ------------------------------------------------------------------

    @Test
    void responsableQueEsCoordinadorRecibeUnSolo() {
        Escenario e = escenario(true);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, e.idCoordinador, e.idCoordinador);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).hasSize(1);
        assertThat(respuesta.intentos().get(0).idUsuario()).isEqualTo(e.idCoordinador);
    }

    @Test
    void sinResponsableReservaParaElCoordinadorAunqueNoNotifique() {
        Escenario e = escenario(false);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        assertThat(respuesta.intentos()).hasSize(1);
        assertThat(respuesta.intentos().get(0).idUsuario()).isEqualTo(e.idCoordinador);
    }

    @Test
    void responsableInactivoEnElGrupoNoSeNotifica() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "INACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        // Sin responsable elegible, el coordinador recibe la alerta aunque el grupo no lo pida (regla 7).
        assertThat(respuesta.intentos()).hasSize(1);
        assertThat(respuesta.intentos().get(0).idUsuario()).isEqualTo(e.idCoordinador);
    }

    @Test
    void reposicionNotificaResponsablePredeterminadoYCoordinador() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, fixturaPlanes.crearMedicamento("Losartán"), "ACTIVO");
        jdbcTemplate.update("UPDATE DETALLE_PLAN SET ID_RESPONSABLE_PREDETERMINADO = ? WHERE ID_DETALLE_PLAN = ?",
                idResponsable, idDetalle);
        long idAlerta = fixturaAlertas.crearAlertaDeDetalle(e.idGrupo, e.idAdulto, idDetalle,
                FixturaAlertas.claveUnica());

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(idAlerta);

        // En la reposición el coordinador se notifica siempre, aunque NOTIFICA_COORDINADOR = 'N'.
        assertThat(respuesta.intentos()).extracting(IntentoReservado::idUsuario)
                .containsExactlyInAnyOrder(e.idCoordinador, idResponsable);
    }

    @Test
    void usuarioSoloConWhatsappHabilitadoQuedaSinCanal() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearCanal(idResponsable, CodigosNotificaciones.CANAL_CORREO, correo(idResponsable), false);
        fixturaAlertas.crearCanal(idResponsable, CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true);

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(e.idAlerta);

        // WHATSAPP no está disponible en el Core (mejorvejez.notificaciones.whatsapp-habilitado=false) y
        // el único responsable deshabilitó CORREO: ningún par queda elegible.
        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
    }

    @Test
    void citaCanceladaOAdultoInactivoNoReservaIntentos() {
        Escenario cancelada = escenario(true);
        fixturaTareas.cambiarEstadoTarea(cancelada.idTarea, CodigosTareas.ESTADO_TAREA_CANCELADA);
        Escenario adultoInactivo = escenario(true);
        jdbcTemplate.update("UPDATE ADULTO_MAYOR SET ID_ESTADO = ? WHERE ID_ADULTO_MAYOR = ?",
                idEstado("ADULTO_MAYOR", "ESTADO", "INACTIVO"), adultoInactivo.idAdulto);

        assertThat(servicio.reservarIntentos(cancelada.idAlerta).intentos()).isEmpty();
        assertThat(servicio.reservarIntentos(adultoInactivo.idAlerta).intentos()).isEmpty();

        assertThat(fixturaAlertas.intentos(cancelada.idAlerta)).isEmpty();
        assertThat(fixturaAlertas.intentos(adultoInactivo.idAlerta)).isEmpty();
        assertThat(fixturaAlertas.estadoAlerta(cancelada.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void reposicionConDetalleInactivoNoSeNotifica() {
        Escenario e = escenario(false);
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, fixturaPlanes.crearMedicamento("Losartán"), "ACTIVO");
        long idAlerta = fixturaAlertas.crearAlertaDeDetalle(e.idGrupo, e.idAdulto, idDetalle,
                FixturaAlertas.claveUnica());
        fixturaPlanes.cambiarEstadoDetalle(idDetalle, "INACTIVO");

        RespuestaReservaIntentos respuesta = servicio.reservarIntentos(idAlerta);

        assertThat(respuesta.intentos()).isEmpty();
        assertThat(fixturaAlertas.intentos(idAlerta)).isEmpty();
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    // ------------------------------------------------------------------
    // Concurrencia
    // ------------------------------------------------------------------

    @Test
    void reservasConcurrentesNoDuplicanPar() throws InterruptedException {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<RespuestaReservaIntentos> resultados = new CopyOnWriteArrayList<>();
        List<Throwable> fallas = new CopyOnWriteArrayList<>();

        new Thread(() -> reservarConcurrente(e.idAlerta, barrera, resultados, fallas, terminaron)).start();
        new Thread(() -> reservarConcurrente(e.idAlerta, barrera, resultados, fallas, terminaron)).start();
        boolean terminaronATiempo = terminaron.await(60, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(fallas).isEmpty();
        assertThat(resultados).hasSize(2);
        int totalReservados = resultados.stream().mapToInt(r -> r.intentos().size()).sum();
        assertThat(totalReservados).isEqualTo(2); // coordinador + responsable, una sola vez entre ambos hilos.

        List<Map<String, Object>> porPar = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_DESTINATARIO, CANAL, COUNT(*) CANTIDAD FROM INTENTO_NOTIFICACION "
                        + "WHERE ID_ALERTA = ? AND ID_ESTADO_ENVIO = ? GROUP BY ID_USUARIO_DESTINATARIO, CANAL",
                e.idAlerta, idEstado(CodigosAlertas.TABLA_INTENTO_NOTIFICACION, CodigosAlertas.CAMPO_ESTADO_ENVIO,
                        CodigosAlertas.ESTADO_ENVIO_PENDIENTE));
        assertThat(porPar).hasSize(2);
        for (Map<String, Object> fila : porPar) {
            assertThat(((Number) fila.get("CANTIDAD")).longValue()).isEqualTo(1L);
        }
    }

    private void reservarConcurrente(long idAlerta, CyclicBarrier barrera,
            List<RespuestaReservaIntentos> resultados, List<Throwable> fallas, CountDownLatch terminaron) {
        try {
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            RespuestaReservaIntentos respuesta = new TransactionTemplate(gestorTransacciones)
                    .execute(estado -> servicio.reservarIntentos(idAlerta));
            resultados.add(respuesta);
        } catch (Throwable error) {
            fallas.add(error);
        } finally {
            terminaron.countDown();
        }
    }

    // ------------------------------------------------------------------
    // Escenario y ayudantes
    // ------------------------------------------------------------------

    /**
     * Grupo con un coordinador ACTIVO (id > 127), {@code
     * NOTIFICA_COORDINADOR} según {@code notificaCoordinador}, un adulto, una
     * tarea PENDIENTE sin asignación y una alerta ABIERTA de atraso de esa
     * tarea.
     */
    private Escenario escenario(boolean notificaCoordinador) {
        long idCoordinador = insertarUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        jdbcTemplate.update("UPDATE GRUPO_FAMILIAR SET NOTIFICA_COORDINADOR = ? WHERE ID_GRUPO = ?",
                notificaCoordinador ? "S" : "N", idGrupo);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idTarea = fixturaTareas.crearTareaManual(idGrupo, idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(2));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(idGrupo, idAdulto, idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        return new Escenario(idCoordinador, idGrupo, idAdulto, idTarea, idAlerta);
    }

    private record Escenario(long idCoordinador, long idGrupo, long idAdulto, long idTarea, long idAlerta) {
    }

    /** Usuario ACTIVO con id explícito > 127, integrante colaborador ACTIVO del grupo. */
    private long usuarioAlto(long idGrupo) {
        return usuarioAlto(idGrupo, "COLABORADOR", "ACTIVO");
    }

    /** Usuario ACTIVO con id explícito > 127, integrante del grupo con el rol y el estado indicados. */
    private long usuarioAlto(long idGrupo, String rol, String estadoIntegrante) {
        long idUsuario = insertarUsuarioAlto();
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuario, rol, estadoIntegrante);
        return idUsuario;
    }

    private long insertarUsuarioAlto() {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', (SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = 'ACTIVO'))",
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + UUID.randomUUID() + "@reserva.mejorvejez.test",
                "Usuario " + idUsuario);
        return idUsuario;
    }

    private String nombre(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT NOMBRE FROM USUARIO WHERE ID_USUARIO = ?", String.class,
                idUsuario);
    }

    private String correo(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT EMAIL FROM USUARIO WHERE ID_USUARIO = ?", String.class,
                idUsuario);
    }

    private String detalleError(long idIntento) {
        return jdbcTemplate.queryForObject("SELECT DETALLE_ERROR FROM INTENTO_NOTIFICACION WHERE ID_INTENTO = ?",
                String.class, idIntento);
    }

    private String referenciaProveedor(long idIntento) {
        return jdbcTemplate.queryForObject(
                "SELECT REFERENCIA_PROVEEDOR FROM INTENTO_NOTIFICACION WHERE ID_INTENTO = ?", String.class,
                idIntento);
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
    }
}
