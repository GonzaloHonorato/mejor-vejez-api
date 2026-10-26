package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.notificaciones.CodigosNotificaciones;
import cl.adndigital.mejorvejez.notificaciones.EnviadorCorreo;
import cl.adndigital.mejorvejez.notificaciones.MensajeNotificacion;
import cl.adndigital.mejorvejez.notificaciones.ResultadoEnvio;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba el notificador de alertas (TSA-45, reglas 7, 8, 9 y 10 de la Fase
 * 5) contra Oracle real (Testcontainers), con el reloj fijo. Cada prueba
 * crea su propio grupo, usuarios (con ids explícitos > 127, para que una
 * comparación con {@code ==} entre {@code Long} falle), tarea y alerta, y
 * filtra siempre por sus propios ids.
 *
 * <p>{@link EnviadorCorreo} es un {@code @MockitoBean} que llama a los
 * métodos reales salvo donde se programa: así {@code canal()} ("CORREO") y
 * {@code disponible()} ({@code true}) ya responden cuando {@code
 * ServicioCanales} los consulta al construirse, y {@code enviar} se programa
 * en cada prueba. Nunca se envía un correo real.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioNotificacionAlertasTest {

    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** Ids de usuario explícitos > 127, en un rango propio (distinto de los de las pruebas de tareas). */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(970_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioNotificacionAlertas servicio;

    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private EnviadorCorreo enviadorCorreo;

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

    // ------------------------------------------------------------------
    // Destinatarios (regla 7)
    // ------------------------------------------------------------------

    @Test
    void atrasoNotificaAlResponsableYAlCoordinador() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        assertThat(idResponsable).isGreaterThan(127L);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(2);
        assertThat(resultado.fallidos()).isZero();
        assertThat(resultado.alertasSinDestinatario()).isEmpty();
        assertThat(resultado.errores()).isEmpty();
        List<Map<String, Object>> intentos = intentosOrdenados(e.idAlerta);
        assertThat(intentos).extracting(i -> ((Number) i.get("ID_USUARIO_DESTINATARIO")).longValue())
                .containsExactly(e.idCoordinador, idResponsable);
        for (Map<String, Object> intento : intentos) {
            assertThat(intento.get("CANAL")).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
            assertThat(((Number) intento.get("NUMERO_INTENTO")).intValue()).isEqualTo(1);
            assertThat(intento.get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
            assertThat((String) intento.get("REFERENCIA_PROVEEDOR")).endsWith("@mejorvejez.test>");
            assertThat(intento.get("DETALLE_ERROR")).isNull();
        }

        ArgumentCaptor<MensajeNotificacion> mensajes = ArgumentCaptor.forClass(MensajeNotificacion.class);
        verify(enviadorCorreo, times(2)).enviar(mensajes.capture());
        assertThat(mensajes.getAllValues()).extracting(MensajeNotificacion::destino)
                .containsExactly(correo(e.idCoordinador), correo(idResponsable));
        assertThat(mensajes.getAllValues()).extracting(MensajeNotificacion::nombreDestinatario)
                .containsExactly(nombre(e.idCoordinador), nombre(idResponsable));
    }

    @Test
    void sinNotificarCoordinadorSoloAlResponsable() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(destinatarios(e.idAlerta)).containsExactly(idResponsable);
    }

    @Test
    void sinResponsableNotificaAlCoordinadorAunqueNoNotifique() {
        Escenario e = escenario(false);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(resultado.alertasSinDestinatario()).isEmpty();
        assertThat(destinatarios(e.idAlerta)).containsExactly(e.idCoordinador);
    }

    @Test
    void responsableQueEsCoordinadorRecibeUnSolo() {
        Escenario e = escenario(true);
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, e.idCoordinador, e.idCoordinador);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(destinatarios(e.idAlerta)).containsExactly(e.idCoordinador);
        verify(enviadorCorreo, times(1)).enviar(any());
    }

    @Test
    void responsableInactivoEnElGrupoNoSeNotifica() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "INACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        // Sin responsable elegible, el coordinador recibe la alerta aunque el grupo no lo pida (regla 7).
        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(destinatarios(e.idAlerta)).containsExactly(e.idCoordinador);
    }

    @Test
    void reposicionNotificaResponsablePredeterminadoYCoordinador() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, fixturaPlanes.crearMedicamento("Losartán"), "ACTIVO");
        jdbcTemplate.update("UPDATE DETALLE_PLAN SET ID_RESPONSABLE_PREDETERMINADO = ? WHERE ID_DETALLE_PLAN = ?",
                idResponsable, idDetalle);
        long idAlerta = fixturaAlertas.crearAlertaDeDetalle(e.idGrupo, e.idAdulto, idDetalle,
                FixturaAlertas.claveUnica());

        ResultadoNotificacion resultado = servicio.notificar(idAlerta);

        // En la reposición el coordinador se notifica siempre, aunque NOTIFICA_COORDINADOR = 'N'.
        assertThat(resultado.enviados()).isEqualTo(2);
        assertThat(destinatarios(idAlerta)).containsExactly(e.idCoordinador, idResponsable);
    }

    // ------------------------------------------------------------------
    // Canales operativos (regla 8)
    // ------------------------------------------------------------------

    @Test
    void usuarioSoloConWhatsappHabilitadoQuedaSinCanal() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearCanal(idResponsable, CodigosNotificaciones.CANAL_CORREO, correo(idResponsable), false);
        fixturaAlertas.crearCanal(idResponsable, CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isZero();
        assertThat(resultado.fallidos()).isZero();
        assertThat(resultado.alertasSinDestinatario()).containsExactly(e.idAlerta);
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
        verify(enviadorCorreo, never()).enviar(any());
    }

    // ------------------------------------------------------------------
    // Intentos y reintentos (reglas 9 y 10)
    // ------------------------------------------------------------------

    @Test
    void falloDeEnvioQuedaRegistradoYSeReintentaHastaElMaximo() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        doReturn(ResultadoEnvio.fallido("Servidor SMTP no responde")).when(enviadorCorreo).enviar(any());
        OffsetDateTime desde = AHORA.minusYears(1);

        ResultadoNotificacion primera = servicio.notificarPendientes(desde, e.idGrupo);
        ResultadoNotificacion segunda = servicio.notificarPendientes(desde, e.idGrupo);
        ResultadoNotificacion tercera = servicio.notificarPendientes(desde, e.idGrupo);
        ResultadoNotificacion cuarta = servicio.notificarPendientes(desde, e.idGrupo);

        assertThat(List.of(primera.fallidos(), segunda.fallidos(), tercera.fallidos(), cuarta.fallidos()))
                .containsExactly(1, 1, 1, 0);
        assertThat(cuarta).isEqualTo(ResultadoNotificacion.vacio());
        verify(enviadorCorreo, times(3)).enviar(any());

        List<Map<String, Object>> intentos = intentosOrdenados(e.idAlerta);
        assertThat(intentos).extracting(i -> ((Number) i.get("NUMERO_INTENTO")).intValue()).containsExactly(1, 2, 3);
        for (Map<String, Object> intento : intentos) {
            assertThat(((Number) intento.get("ID_USUARIO_DESTINATARIO")).longValue()).isEqualTo(idResponsable);
            assertThat(intento.get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
            assertThat(intento.get("DETALLE_ERROR")).isEqualTo("Servidor SMTP no responde");
            assertThat(intento.get("REFERENCIA_PROVEEDOR")).isNull();
        }
        assertThat(fixturaAlertas.estadoAlerta(e.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        assertThat(fixturaAlertas.alertasDeTarea(e.idTarea)).hasSize(1);
    }

    @Test
    void paresYaEnviadosNoSeReenvian() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 1,
                CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        fixturaAlertas.crearIntento(e.idAlerta, idResponsable, CodigosNotificaciones.CANAL_CORREO, 2,
                CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);
        ResultadoNotificacion otraVez = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(otraVez).isEqualTo(ResultadoNotificacion.vacio());
        verify(enviadorCorreo, times(1)).enviar(any());
        List<Map<String, Object>> intentosCoordinador = intentosOrdenados(e.idAlerta).stream()
                .filter(i -> ((Number) i.get("ID_USUARIO_DESTINATARIO")).longValue() == e.idCoordinador)
                .toList();
        assertThat(intentosCoordinador).hasSize(1);
        assertThat(intentosCoordinador.get(0).get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(3);
    }

    @Test
    void enviadorQueLanzaNoDeshaceLosEnviosPrevios() {
        Escenario e = escenario(true);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        String correoResponsable = correo(idResponsable);
        // El coordinador (id menor) se envía bien; el envío al responsable lanza con su destino en el mensaje.
        doAnswer(invocacion -> ResultadoEnvio.exitoso("<ok@mejorvejez.test>"))
                .doAnswer(invocacion -> {
                    throw new IllegalStateException("No se pudo enviar a " + correoResponsable);
                })
                .when(enviadorCorreo).enviar(any());

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(resultado.fallidos()).isEqualTo(1);
        assertThat(resultado.errores()).isEmpty();
        List<Map<String, Object>> intentos = intentosOrdenados(e.idAlerta);
        assertThat(intentos).hasSize(2);
        assertThat(((Number) intentos.get(0).get("ID_USUARIO_DESTINATARIO")).longValue()).isEqualTo(e.idCoordinador);
        assertThat(intentos.get(0).get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        assertThat(((Number) intentos.get(1).get("ID_USUARIO_DESTINATARIO")).longValue()).isEqualTo(idResponsable);
        assertThat(intentos.get(1).get("CODIGO_ESTADO")).isEqualTo(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        assertThat(intentos.get(1).get("DETALLE_ERROR")).isEqualTo("IllegalStateException");
        assertThat(intentos.get(1).get("REFERENCIA_PROVEEDOR")).isNull();
    }

    // ------------------------------------------------------------------
    // Vigencia del origen antes de enviar
    // ------------------------------------------------------------------

    @Test
    void reintentoNoNotificaSiLaTareaYaSeCompleto() {
        Escenario e = escenario(false);
        long idResponsable = usuarioAlto(e.idGrupo, "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(e.idTarea, e.idGrupo, idResponsable, e.idCoordinador);
        doReturn(ResultadoEnvio.fallido("Servidor SMTP no responde")).when(enviadorCorreo).enviar(any());
        OffsetDateTime desde = AHORA.minusYears(1);

        ResultadoNotificacion primera = servicio.notificarPendientes(desde, e.idGrupo);
        fixturaTareas.cambiarEstadoTarea(e.idTarea, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        ResultadoNotificacion reintento = servicio.notificarPendientes(desde, e.idGrupo);

        assertThat(primera.fallidos()).isEqualTo(1);
        assertThat(reintento).isEqualTo(ResultadoNotificacion.vacio());
        verify(enviadorCorreo, times(1)).enviar(any());
        assertThat(fixturaAlertas.intentos(e.idAlerta)).hasSize(1);
        assertThat(fixturaAlertas.estadoAlerta(e.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void citaCanceladaOAdultoInactivoNoSeNotifican() {
        Escenario cancelada = escenario(true);
        fixturaTareas.cambiarEstadoTarea(cancelada.idTarea, CodigosTareas.ESTADO_TAREA_CANCELADA);
        Escenario adultoInactivo = escenario(true);
        jdbcTemplate.update("UPDATE ADULTO_MAYOR SET ID_ESTADO = (SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                + "WHERE NOMBRE_TABLA = 'ADULTO_MAYOR' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = 'INACTIVO') "
                + "WHERE ID_ADULTO_MAYOR = ?", adultoInactivo.idAdulto);

        assertThat(servicio.notificar(cancelada.idAlerta)).isEqualTo(ResultadoNotificacion.vacio());
        assertThat(servicio.notificar(adultoInactivo.idAlerta)).isEqualTo(ResultadoNotificacion.vacio());

        verify(enviadorCorreo, never()).enviar(any());
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

        ResultadoNotificacion resultado = servicio.notificar(idAlerta);

        assertThat(resultado).isEqualTo(ResultadoNotificacion.vacio());
        assertThat(fixturaAlertas.intentos(idAlerta)).isEmpty();
        verify(enviadorCorreo, never()).enviar(any());
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void alertaCerradaNoSeNotifica() {
        Escenario e = escenario(true);
        fixturaAlertas.cambiarEstadoAlerta(e.idAlerta, CodigosAlertas.ESTADO_ALERTA_CERRADA);

        ResultadoNotificacion resultado = servicio.notificar(e.idAlerta);

        assertThat(resultado).isEqualTo(ResultadoNotificacion.vacio());
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
        verify(enviadorCorreo, never()).enviar(any());
    }

    @Test
    void notificarNoCambiaLaTarea() {
        Escenario e = escenario(true);
        OffsetDateTime actualizacion = OffsetDateTime.parse("2026-11-01T09:30:00-03:00");
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET FECHA_ACTUALIZACION = ? WHERE ID_TAREA = ?", actualizacion,
                e.idTarea);

        servicio.notificar(e.idAlerta);

        assertThat(fixturaTareas.estadoTarea(e.idTarea)).isEqualTo(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        OffsetDateTime releida = jdbcTemplate.queryForObject(
                "SELECT FECHA_ACTUALIZACION FROM TAREA_CUIDADO WHERE ID_TAREA = ?", OffsetDateTime.class, e.idTarea);
        assertThat(releida.isEqual(actualizacion)).isTrue();
        assertThat(fixturaAlertas.estadoAlerta(e.idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    @Test
    void falloInesperadoEnUnaAlertaSeCuentaYSigueConLasDemas() {
        Escenario e = escenario(false);
        long idTareaDos = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(1));
        long idAlertaDos = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTareaDos,
                CodigosAlertas.TIPO_CITA_PROXIMA, FixturaAlertas.claveUnica());
        // La primera alerta revienta DESPUÉS del envío (un resultado nulo hace fallar el registro del intento,
        // fuera del try/catch del envío); la segunda se envía bien. Una excepción del propio enviador ya no
        // sirve para esto: se registra como intento FALLIDO (ver enviadorQueLanzaNoDeshaceLosEnviosPrevios).
        doAnswer(invocacion -> null)
                .doAnswer(invocacion -> ResultadoEnvio.exitoso("<ok@mejorvejez.test>"))
                .when(enviadorCorreo).enviar(any());

        ResultadoNotificacion resultado = servicio.notificarPendientes(AHORA.minusYears(1), e.idGrupo);

        assertThat(resultado.errores()).containsExactly("Alerta " + e.idAlerta + ": NullPointerException");
        assertThat(resultado.enviados()).isEqualTo(1);
        assertThat(fixturaAlertas.intentos(e.idAlerta)).isEmpty();
        assertThat(fixturaAlertas.intentos(idAlertaDos)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Escenario
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
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + UUID.randomUUID() + "@alertas.mejorvejez.test",
                "Usuario " + idUsuario);
        return idUsuario;
    }

    private String correo(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT EMAIL FROM USUARIO WHERE ID_USUARIO = ?", String.class, idUsuario);
    }

    private String nombre(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT NOMBRE FROM USUARIO WHERE ID_USUARIO = ?", String.class, idUsuario);
    }

    /** Intentos de la alerta con su estado como código, por destinatario, canal y número. */
    private List<Map<String, Object>> intentosOrdenados(long idAlerta) {
        return jdbcTemplate.queryForList(
                "SELECT i.ID_USUARIO_DESTINATARIO, i.CANAL, i.NUMERO_INTENTO, i.DETALLE_ERROR, "
                        + "i.REFERENCIA_PROVEEDOR, ce.CODIGO_ESTADO FROM INTENTO_NOTIFICACION i "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = i.ID_ESTADO_ENVIO WHERE i.ID_ALERTA = ? "
                        + "ORDER BY i.ID_USUARIO_DESTINATARIO, i.CANAL, i.NUMERO_INTENTO",
                idAlerta);
    }

    private List<Long> destinatarios(long idAlerta) {
        return jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_DESTINATARIO FROM INTENTO_NOTIFICACION WHERE ID_ALERTA = ? "
                        + "ORDER BY ID_USUARIO_DESTINATARIO",
                Long.class, idAlerta);
    }
}
