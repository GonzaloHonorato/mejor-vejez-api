package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
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
 * Prueba el detector de atrasos (TSA-43, reglas 1, 2, 5, 6, 10 y 11 de la
 * Fase 5) contra Oracle real (Testcontainers), con el reloj fijo en el lunes
 * 2026-11-02 10:00 de Santiago. Cada prueba crea su propio grupo (con un
 * coordinador de id > 127), adulto y tareas, y corre {@code ejecutar(...,
 * idGrupo)}: la BD es compartida. Solo la corrida manual del administrador
 * (global por diseño) no filtra, y en ella la consulta de candidatas y el
 * paso "notificar pendientes" se dejan vacíos con spies.
 *
 * <p>{@link EnviadorCorreo} es un {@code @MockitoBean} que llama a los
 * métodos reales salvo donde se programa, igual que en {@code
 * ServicioNotificacionAlertasTest}: {@code ServicioCanales} consulta {@code
 * canal()} y {@code disponible()} al construirse. Nunca se envía un correo
 * real.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioDeteccionAtrasosTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00). */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** Ids de usuario explícitos > 127, en un rango propio (distinto del de las otras pruebas). */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(980_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioDeteccionAtrasos servicio;

    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private EnviadorCorreo enviadorCorreo;

    @MockitoSpyBean
    private RepositorioConsultaAlertas consultaAlertas;

    @MockitoSpyBean
    private ServicioNotificacionAlertas servicioNotificacion;

    @MockitoSpyBean
    private CreadorAlertas creadorAlertas;

    @Autowired
    private ApplicationContext contexto;

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
        doAnswer(invocacion -> ResultadoEnvio.exitoso("<" + UUID.randomUUID() + "@mejorvejez.test>"))
                .when(enviadorCorreo).enviar(any());
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Qué tareas generan alerta (TSA-43, regla 2)
    // ------------------------------------------------------------------

    /** TSA-43: una tarea con confirmación final antes del límite no genera alerta. */
    @Test
    void tareaFinalizadaAntesDelLimiteNoGeneraAlerta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));
        fixturaTareas.cambiarEstadoTarea(idTarea, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET FECHA_CONFIRMACION = ?, ID_USUARIO_CONFIRMADOR = ? "
                + "WHERE ID_TAREA = ?", AHORA.withHour(9), e.idCoordinador, idTarea);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).isEmpty();
    }

    /** TSA-43: PENDIENTE pasado el límite genera la alerta ATRASO_TAREA y la notifica. */
    @Test
    void tareaPendienteTrasElLimiteGeneraAlerta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        Map<String, Object> alerta = alertas.get(0);
        long idAlerta = numero(alerta, "ID_ALERTA");
        assertThat(alerta.get("CLAVE_ALERTA")).isEqualTo("ATR-" + idTarea);
        assertThat(alerta.get("TIPO_ALERTA")).isEqualTo(CodigosAlertas.TIPO_ATRASO_TAREA);
        assertThat(alerta.get("MENSAJE_ALERTA")).isEqualTo("«Tarea fixtura manual» de Adulto fixtura, "
                + "programada para el 02-11-2026 a las 09:00, sigue sin confirmación final.");
        assertThat(numero(alerta, "ID_GRUPO")).isEqualTo(e.idGrupo);
        assertThat(numero(alerta, "ID_ADULTO_MAYOR")).isEqualTo(e.idAdulto);
        assertThat(alerta.get("ID_DETALLE_PLAN")).isNull();
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        assertThat(instante(idAlerta, "FECHA_PROGRAMADA")).isEqualTo(AHORA.withHour(9).toInstant());
        assertThat(instante(idAlerta, "FECHA_DETECCION")).isEqualTo(AHORA.toInstant());

        List<Map<String, Object>> intentos = intentosConEstado(idAlerta);
        assertThat(intentos).hasSize(1);
        assertThat(numero(intentos.get(0), "ID_USUARIO_DESTINATARIO")).isEqualTo(e.idCoordinador);
        assertThat(intentos.get(0).get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
    }

    @Test
    void enCursoTrasElLimiteGeneraAlerta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));
        fixturaTareas.cambiarEstadoTarea(idTarea, CodigosTareas.ESTADO_TAREA_EN_CURSO);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).hasSize(1);
    }

    /** Tarea 09:30 + 30 min de tolerancia = 10:00: "ahora > límite" es falso con el reloj a las 10:00. */
    @Test
    void justoEnElLimiteNoGeneraAlerta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9).withMinute(30));

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).isEmpty();
    }

    @Test
    void reejecutarNoDuplica() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));

        RespuestaEjecucion primera = servicio.ejecutar(AHORA, e.idGrupo);
        RespuestaEjecucion segunda = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(primera.cantidadCreada()).isEqualTo(1);
        assertThat(segunda.cantidadCreada()).isZero();
        assertThat(segunda.cantidadErrores()).isZero();
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        // La segunda corrida no reenvía: el par ya tiene un intento ENVIADO (regla 9).
        assertThat(fixturaAlertas.intentos(numero(alertas.get(0), "ID_ALERTA"))).hasSize(1);
    }

    @Test
    void noRealizadaYCanceladaNoGeneran() {
        Escenario e = escenario();
        long idNoRealizada = tareaManual(e, AHORA.withHour(8));
        fixturaTareas.cambiarEstadoTarea(idNoRealizada, CodigosTareas.ESTADO_TAREA_NO_REALIZADA);
        long idCancelada = tareaManual(e, AHORA.withHour(8));
        fixturaTareas.cambiarEstadoTarea(idCancelada, CodigosTareas.ESTADO_TAREA_CANCELADA);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idNoRealizada)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idCancelada)).isEmpty();
    }

    @Test
    void generaAlertaNoNoGenera() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(8));
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET GENERA_ALERTA = 'N' WHERE ID_TAREA = ?", idTarea);

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).isEmpty();
    }

    /** Adulto, plan, detalle o grupo inactivo: ninguna alerta (regla 2). El control: el mismo caso vigente sí genera. */
    @Test
    void adultoPlanDetalleOGrupoInactivoNoGenera() {
        // Adulto inactivo (tarea manual).
        Escenario adultoInactivo = escenario();
        long idTareaAdulto = tareaManual(adultoInactivo, AHORA.withHour(8));
        jdbcTemplate.update("UPDATE ADULTO_MAYOR SET ID_ESTADO = ? WHERE ID_ADULTO_MAYOR = ?",
                idEstado("ADULTO_MAYOR", "ESTADO", "INACTIVO"), adultoInactivo.idAdulto);

        // Plan inactivo (tarea de medicamento).
        Escenario planInactivo = escenario();
        long idTareaPlan = tareaMedicamento(planInactivo, "INACTIVO", "ACTIVO", AHORA.withHour(8));

        // Detalle inactivo (tarea de medicamento).
        Escenario detalleInactivo = escenario();
        long idTareaDetalle = tareaMedicamento(detalleInactivo, "ACTIVO", "INACTIVO", AHORA.withHour(8));

        // Grupo inactivo (por JDBC).
        Escenario grupoInactivo = escenario();
        long idTareaGrupo = tareaManual(grupoInactivo, AHORA.withHour(8));
        jdbcTemplate.update("UPDATE GRUPO_FAMILIAR SET ID_ESTADO = ? WHERE ID_GRUPO = ?",
                idEstado("GRUPO_FAMILIAR", "ESTADO", "INACTIVO"), grupoInactivo.idGrupo);

        // Control: medicamento con plan y detalle vigentes sí genera.
        Escenario vigente = escenario();
        long idTareaVigente = tareaMedicamento(vigente, "ACTIVO", "ACTIVO", AHORA.withHour(8));

        for (Escenario e : List.of(adultoInactivo, planInactivo, detalleInactivo, grupoInactivo)) {
            assertThat(servicio.ejecutar(AHORA, e.idGrupo).cantidadCreada()).isZero();
        }
        assertThat(servicio.ejecutar(AHORA, vigente.idGrupo).cantidadCreada()).isEqualTo(1);

        assertThat(fixturaAlertas.alertasDeTarea(idTareaAdulto)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idTareaPlan)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idTareaDetalle)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idTareaGrupo)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idTareaVigente)).hasSize(1);
    }

    /** Tareas de antes de {@code ahora − 48 h} no generan (regla 2); justo en el borde sí. */
    @Test
    void tareaMasViejaQueLaRetrospectivaNoGenera() {
        Escenario e = escenario();
        long idVieja = tareaManual(e, AHORA.minusHours(48).minusMinutes(1));
        long idEnElBorde = tareaManual(e, AHORA.minusHours(48));

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(fixturaAlertas.alertasDeTarea(idVieja)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idEnElBorde)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Creación transaccional (regla 5)
    // ------------------------------------------------------------------

    /** Una tarea confirmada entre la consulta de candidatas y la creación no genera alerta (regla 5). */
    @Test
    void tareaConfirmadaDespuesDeLaConsultaNoGeneraAlerta() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));
        doAnswer(invocacion -> {
            Object candidatas = invocacion.callRealMethod();
            // Otra transacción (autocommit de JdbcTemplate) confirma la tarea antes de que se cree la alerta.
            fixturaTareas.cambiarEstadoTarea(idTarea, CodigosTareas.ESTADO_TAREA_COMPLETADA);
            return candidatas;
        }).when(consultaAlertas).tareasAtrasadasCandidatas(any(), any(), eq(e.idGrupo));

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        verify(consultaAlertas).tareasAtrasadasCandidatas(any(), any(), eq(e.idGrupo));
        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).isEmpty();
    }

    @Test
    void corridasConcurrentesCreanUnaSolaAlerta() throws Exception {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));

        CyclicBarrier barrera = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Future<RespuestaEjecucion>> corridas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                corridas.add(hilos.submit(() -> {
                    barrera.await(30, TimeUnit.SECONDS);
                    return servicio.ejecutar(AHORA, e.idGrupo);
                }));
            }
            int creadas = 0;
            for (Future<RespuestaEjecucion> corrida : corridas) {
                RespuestaEjecucion respuesta = corrida.get(120, TimeUnit.SECONDS);
                assertThat(respuesta.cantidadErrores()).isZero();
                creadas += respuesta.cantidadCreada();
            }
            assertThat(creadas).isEqualTo(1);
        } finally {
            hilos.shutdownNow();
        }

        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        assertThat(fixturaAlertas.intentos(numero(alertas.get(0), "ID_ALERTA"))).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Resultado de la corrida (regla 11)
    // ------------------------------------------------------------------

    /** TSA-45: si el envío falla, la alerta persiste y la corrida queda PARCIAL con el intento FALLIDO. */
    @Test
    void falloDeEnvioDejaCorridaParcialYAlertaPersistida() {
        doReturn(ResultadoEnvio.fallido("El envío de correo no está configurado."))
                .when(enviadorCorreo).enviar(any());
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isEqualTo(1);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_PARCIAL));
        assertThat(respuesta.resumenErrores()).doesNotContain("@");
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        long idAlerta = numero(alertas.get(0), "ID_ALERTA");
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        List<Map<String, Object>> intentos = intentosConEstado(idAlerta);
        assertThat(intentos).hasSize(1);
        assertThat(intentos.get(0).get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
    }

    /**
     * Una alerta creada en esta corrida sin ningún destinatario con canal
     * operativo cuenta como error (regla 11); en la corrida siguiente ya no.
     */
    @Test
    void alertaNuevaSinDestinatariosCuentaComoErrorSoloEnSuCorrida() {
        Escenario e = escenario();
        // El coordinador deshabilita su correo: sin canal operativo (regla 8).
        fixturaAlertas.crearCanal(e.idCoordinador, CodigosNotificaciones.CANAL_CORREO,
                "otro-" + UUID.randomUUID() + "@alertas.mejorvejez.test", false);
        long idTarea = tareaManual(e, AHORA.withHour(9));

        RespuestaEjecucion primera = servicio.ejecutar(AHORA, e.idGrupo);
        long idAlerta = numero(fixturaAlertas.alertasDeTarea(idTarea).get(0), "ID_ALERTA");
        RespuestaEjecucion segunda = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(primera.cantidadCreada()).isEqualTo(1);
        assertThat(primera.cantidadErrores()).isEqualTo(1);
        assertThat(primera.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_PARCIAL));
        assertThat(primera.resumenErrores())
                .isEqualTo("Alerta " + idAlerta + ": sin destinatarios con canal operativo");
        assertThat(segunda.cantidadCreada()).isZero();
        assertThat(segunda.cantidadErrores()).isZero();
        assertThat(segunda.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        assertThat(segunda.resumenErrores()).isNull();
    }

    /** Un fallo al crear una alerta se cuenta como error y la corrida sigue con las demás: PARCIAL. */
    @Test
    void falloAlCrearUnaAlertaSeCuentaYLaCorridaSigue() {
        Escenario e = escenario();
        long idFalla = tareaManual(e, AHORA.withHour(8));
        long idSigue = tareaManual(e, AHORA.withHour(9));
        doThrow(new IllegalStateException("Fallo inyectado")).when(creadorAlertas).crearAtraso(eq(idFalla), any());

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isEqualTo(1);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_PARCIAL));
        assertThat(respuesta.resumenErrores()).isEqualTo("Tarea " + idFalla + ": IllegalStateException");
        assertThat(fixturaAlertas.alertasDeTarea(idFalla)).isEmpty();
        assertThat(fixturaAlertas.alertasDeTarea(idSigue)).hasSize(1);
    }

    /** Sin nada creado ni enviado y con errores, la corrida queda FALLIDA. */
    @Test
    void soloFallosDejaCorridaFallida() {
        Escenario e = escenario();
        long idFalla = tareaManual(e, AHORA.withHour(8));
        doThrow(new IllegalStateException("Fallo inyectado")).when(creadorAlertas).crearAtraso(eq(idFalla), any());

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.cantidadErrores()).isEqualTo(1);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_FALLIDA));
    }

    @Test
    void corridaRegistraDeteccionAtrasosConVentana() {
        Escenario e = escenario();

        RespuestaEjecucion respuesta = servicio.ejecutar(AHORA, e.idGrupo);

        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS);
        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(AHORA.minusHours(48));
        assertThat(respuesta.finVentana()).isAtSameInstantAs(AHORA);
        assertThat(respuesta.fechaFin()).isNotNull();
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        verify(servicioNotificacion).notificarPendientes(eq(AHORA.minusHours(48)), eq(e.idGrupo));
    }

    // ------------------------------------------------------------------
    // Corrida manual y programada (regla 12)
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
        doReturn(List.of()).when(consultaAlertas).tareasAtrasadasCandidatas(any(), any(), isNull());
        doReturn(ResultadoNotificacion.vacio()).when(servicioNotificacion).notificarPendientes(any(), isNull());

        RespuestaEjecucion respuesta = servicio.ejecutarManual();

        verify(consultaAlertas).tareasAtrasadasCandidatas(eq(AHORA.minusHours(48)), eq(AHORA), isNull());
        verify(servicioNotificacion).notificarPendientes(eq(AHORA.minusHours(48)), isNull());
        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ea.ID_USUARIO_ACTOR, ea.ID_GRUPO, ea.NOMBRE_SERVICIO, ce.CODIGO_ESTADO AS RESULTADO "
                        + "FROM EVENTO_AUDITORIA ea JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.TIPO_ENTIDAD = 'EJECUCION_PROCESO' AND ea.ID_ENTIDAD = ? AND ea.CODIGO_ACCION = ?",
                String.valueOf(respuesta.idEjecucion()), AccionesAlertas.EJECUTAR_DETECCION_ATRASOS);
        assertThat(eventos).hasSize(1);
        assertThat(numero(eventos.get(0), "ID_USUARIO_ACTOR")).isEqualTo(idAdministrador);
        assertThat(eventos.get(0).get("ID_GRUPO")).isNull();
        assertThat(eventos.get(0).get("NOMBRE_SERVICIO")).isEqualTo(CodigosAlertas.MODULO);
        assertThat(eventos.get(0).get("RESULTADO")).isEqualTo("EXITO");
    }

    /** Si la corrida manual falla fuera de los pasos por tarea, relanza y queda auditada FALLO en transacción propia. */
    @Test
    void corridaManualFallidaQuedaAuditadaComoFallo() {
        String uidAdministrador = FixturaGrupos.uidUnico();
        long idAdministrador = fixturaGrupos.crearUsuario(uidAdministrador, true);
        autenticar(uidAdministrador);
        doThrow(new IllegalStateException("Fallo inyectado en la consulta de candidatas"))
                .when(consultaAlertas).tareasAtrasadasCandidatas(any(), any(), isNull());

        assertThatThrownBy(() -> servicio.ejecutarManual()).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Fallo inyectado en la consulta de candidatas");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ea.ID_ENTIDAD, ea.ID_GRUPO, DBMS_LOB.SUBSTR(ea.DETALLES_JSON, 4000, 1) AS DETALLES, "
                        + "ce.CODIGO_ESTADO AS RESULTADO "
                        + "FROM EVENTO_AUDITORIA ea JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.TIPO_ENTIDAD = 'EJECUCION_PROCESO' AND ea.CODIGO_ACCION = ? "
                        + "AND ea.ID_USUARIO_ACTOR = ?",
                AccionesAlertas.EJECUTAR_DETECCION_ATRASOS, idAdministrador);
        assertThat(eventos).hasSize(1);
        Map<String, Object> evento = eventos.get(0);
        assertThat(evento.get("RESULTADO")).isEqualTo("FALLO");
        assertThat(evento.get("ID_GRUPO")).isNull();
        assertThat((String) evento.get("DETALLES")).doesNotContain("Fallo inyectado");
        long idEjecucion = Long.parseLong((String) evento.get("ID_ENTIDAD"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM EJECUCION_PROCESO e JOIN CATALOGO_ESTADOS ce "
                        + "ON ce.ID_ESTADO = e.ID_ESTADO WHERE e.ID_EJECUCION = ?",
                String.class, idEjecucion)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_FALLIDA);
    }

    /** En las pruebas el disparo programado está apagado ({@code mejorvejez.procesos.habilitados=false}). */
    @Test
    void programadorAlertasNoSeRegistraEnLasPruebas() {
        assertThat(contexto.getBeanNamesForType(ProgramadorAlertas.class)).isEmpty();
        assertThat(contexto.getEnvironment().getProperty("mejorvejez.alertas.cron-deteccion"))
                .isEqualTo("0 */5 * * * *");
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

    /** Tarea manual de cita, PENDIENTE, con la tolerancia por defecto (30 min) y GENERA_ALERTA = 'S'. */
    private long tareaManual(Escenario e, OffsetDateTime horario) {
        return fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario);
    }

    /** Tarea de MEDICAMENTOS PENDIENTE de un detalle y plan con los estados indicados. */
    private long tareaMedicamento(Escenario e, String estadoPlan, String estadoDetalle, OffsetDateTime horario) {
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, estadoPlan);
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, fixturaPlanes.crearMedicamento("Losartán"),
                estadoDetalle);
        return fixturaTareas.crearTareaMedicamento(e.idGrupo, e.idAdulto, idDetalle, horario,
                CodigosTareas.ESTADO_TAREA_PENDIENTE, FixturaTareas.claveUnica());
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

    private List<Map<String, Object>> intentosConEstado(long idAlerta) {
        return jdbcTemplate.queryForList(
                "SELECT i.ID_USUARIO_DESTINATARIO, ce.CODIGO_ESTADO FROM INTENTO_NOTIFICACION i "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = i.ID_ESTADO_ENVIO WHERE i.ID_ALERTA = ? "
                        + "ORDER BY i.ID_USUARIO_DESTINATARIO",
                idAlerta);
    }

    private Instant instante(long idAlerta, String columna) {
        return jdbcTemplate.queryForObject("SELECT " + columna + " FROM ALERTA_CUIDADO WHERE ID_ALERTA = ?",
                OffsetDateTime.class, idAlerta).toInstant();
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
