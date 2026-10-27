package cl.adndigital.mejorvejez.sucesion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.FixturaAlertas;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.grupos.ServicioIntegrantes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.ServicioEstadoTareas;
import cl.adndigital.mejorvejez.tareas.SolicitudCambiarEstadoTarea;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioCompletarSucesion} (reglas 21 y 22 de la Fase 7)
 * contra Oracle real (Testcontainers), con el reloj de negocio fijo el lunes
 * 2026-11-02 10:00 (America/Santiago), igual que {@link
 * ServicioSucesionTest}. Datos propios por prueba (grupos/usuarios con
 * UUID); nunca se cuentan tablas completas (la BD es compartida). Todos los
 * usuarios llevan ids explícitos &gt; 127.
 *
 * <p>{@link ServicioAuditoria} es un {@link MockitoSpyBean} (métodos reales
 * salvo en {@link #falloDuranteLaTransferenciaNoDejaSinCoordinador}, que lo
 * hace fallar al auditar {@code COMPLETAR_SUCESION}); Mockito lo reinicia
 * después de cada prueba.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioCompletarSucesionTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final OffsetDateTime AHORA = HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

    private static final String MOTIVO_REASIGNACION = "Sucesión de la coordinación";

    /**
     * Rango propio de ids de usuario explícitos, sin cruzarse con los de
     * otras pruebas que comparten la BD (900, 950, 960, 970, 980, 981,
     * 990 y 993 millones; ver {@link ServicioSucesionTest}).
     */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(996_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioCompletarSucesion servicio;

    @Autowired
    private ServicioSucesion servicioSucesion;

    @Autowired
    private ServicioIntegrantes servicioIntegrantes;

    @Autowired
    private ServicioEstadoTareas servicioEstadoTareas;

    @MockitoSpyBean
    private ServicioAuditoria servicioAuditoria;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Camino feliz (regla 21)
    // ------------------------------------------------------------------

    @Test
    void completarSinCondicionesCambiaRolesYCompleta() throws Exception {
        Escenario e = escenario();
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        RespuestaPropuesta completada = servicio.completar(e.idGrupo(), idPropuesta,
                new SolicitudCompletarSucesion(null));

        assertThat(completada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_COMPLETADA);
        assertThat(completada.fechaCompletado()).isEqualTo(AHORA);
        assertThat(completada.fechaCompletado().getOffset()).isEqualTo(AHORA.getOffset());
        assertThat(completada.condicionesPendientes()).isEmpty();
        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("COMPLETADA");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PROPUESTA_SUCESION WHERE ID_PROPUESTA = ? AND FECHA_COMPLETADO IS NOT NULL",
                Long.class, idPropuesta)).isEqualTo(1L);

        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COLABORADOR");
        assertThat(estadoIntegrante(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("ACTIVO");
        assertThat(estadoIntegrante(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("ACTIVO");

        long idRolCoordinador = idRol("COORDINADOR");
        long idRolColaborador = idRol("COLABORADOR");
        List<Map<String, Object>> cambiosDeRol = eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE");
        assertThat(cambiosDeRol).hasSize(2);
        for (Map<String, Object> evento : cambiosDeRol) {
            assertThat(evento.get("NOMBRE_SERVICIO")).isEqualTo("grupos");
            assertThat(evento.get("TIPO_ENTIDAD")).isEqualTo("INTEGRANTE_GRUPO");
            assertThat(evento.get("RESULTADO")).isEqualTo("EXITO");
            assertThat(((Number) evento.get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(e.saliente().idUsuario());
            assertThat(detalles(evento)).containsEntry("motivo", "SUCESION").doesNotContainKey("accesoAdministrador");
        }
        Map<String, Object> ascenso = eventoDeEntidad(cambiosDeRol, e.idIntegranteCandidato());
        assertThat(((Number) detalles(ascenso).get("idRolAnterior")).longValue()).isEqualTo(idRolColaborador);
        assertThat(((Number) detalles(ascenso).get("idRolNuevo")).longValue()).isEqualTo(idRolCoordinador);
        Map<String, Object> salida = eventoDeEntidad(cambiosDeRol, e.idIntegranteSaliente());
        assertThat(((Number) detalles(salida).get("idRolAnterior")).longValue()).isEqualTo(idRolCoordinador);
        assertThat(((Number) detalles(salida).get("idRolNuevo")).longValue()).isEqualTo(idRolColaborador);

        List<Map<String, Object>> completar = eventos(e.idGrupo(), "COMPLETAR_SUCESION");
        assertThat(completar).hasSize(1);
        Map<String, Object> evento = completar.get(0);
        assertThat(evento.get("NOMBRE_SERVICIO")).isEqualTo("sucesion");
        assertThat(evento.get("TIPO_ENTIDAD")).isEqualTo("PROPUESTA_SUCESION");
        assertThat(evento.get("ID_ENTIDAD")).isEqualTo(String.valueOf(idPropuesta));
        assertThat(evento.get("RESULTADO")).isEqualTo("EXITO");
        Map<String, Object> detalles = detalles(evento);
        assertThat(((Number) detalles.get("idUsuarioSaliente")).longValue()).isEqualTo(e.saliente().idUsuario());
        assertThat(((Number) detalles.get("idUsuarioCandidato")).longValue()).isEqualTo(e.candidato().idUsuario());
        assertThat(((Number) detalles.get("reasignaciones")).intValue()).isZero();
        assertThat(detalles).doesNotContainKey("accesoAdministrador");
    }

    @Test
    void completarConReasignaciones() throws Exception {
        Escenario e = escenario();
        UsuarioAlto colaborador = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        long idTareaUno = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idTareaDos = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(2));
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        RespuestaPropuesta completada = servicio.completar(e.idGrupo(), idPropuesta,
                new SolicitudCompletarSucesion(List.of(
                        new SolicitudReasignacion(idTareaUno, e.candidato().idUsuario()),
                        new SolicitudReasignacion(idTareaDos, colaborador.idUsuario()))));

        assertThat(completada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_COMPLETADA);
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COLABORADOR");

        assertThat(asignaciones(idTareaUno)).containsExactly(
                List.of(e.saliente().idUsuario(), "REEMPLAZADA"),
                List.of(e.candidato().idUsuario(), "ACTIVO"));
        assertThat(asignaciones(idTareaDos)).containsExactly(
                List.of(e.saliente().idUsuario(), "REEMPLAZADA"),
                List.of(colaborador.idUsuario(), "ACTIVO"));
        assertThat(motivoAsignacionActiva(idTareaUno)).isEqualTo(MOTIVO_REASIGNACION);
        assertThat(motivoAsignacionActiva(idTareaDos)).isEqualTo(MOTIVO_REASIGNACION);

        List<Map<String, Object>> reasignaciones = eventos(e.idGrupo(), "REASIGNAR_TAREA");
        assertThat(reasignaciones).hasSize(2);
        assertThat(reasignaciones).extracting(evento -> evento.get("ID_ENTIDAD"))
                .containsExactlyInAnyOrder(String.valueOf(idTareaUno), String.valueOf(idTareaDos));
        List<Map<String, Object>> completar = eventos(e.idGrupo(), "COMPLETAR_SUCESION");
        assertThat(completar).hasSize(1);
        assertThat(((Number) detalles(completar.get(0)).get("reasignaciones")).intValue()).isEqualTo(2);
    }

    @Test
    void tareaResueltaAntesDejaDeSerCondicion() {
        Escenario e = escenario();
        long idTarea = tareaAsignada(e, e.saliente().idUsuario(), AHORA.minusHours(1));
        long idPropuesta = propuestaAceptada(e);

        // F4: el saliente (responsable) confirma la tarea antes de completar la sucesión.
        autenticar(e.saliente().uid());
        servicioEstadoTareas.cambiarEstado(e.idGrupo(), idTarea, new SolicitudCambiarEstadoTarea(
                idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.ESTADO_TAREA_COMPLETADA), null));

        RespuestaPropuesta completada = servicio.completar(e.idGrupo(), idPropuesta,
                new SolicitudCompletarSucesion(List.of()));

        assertThat(completada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_COMPLETADA);
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COORDINADOR");
        // La asignación de la tarea resuelta no se toca.
        assertThat(asignaciones(idTarea)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
    }

    @Test
    void administradorCompletaConTrazabilidad() throws Exception {
        Escenario e = escenario();
        UsuarioAlto administrador = crearUsuarioAlto(true);
        long idTarea = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idPropuesta = propuestaAceptada(e);
        autenticar(administrador.uid());

        RespuestaPropuesta completada = servicio.completar(e.idGrupo(), idPropuesta,
                new SolicitudCompletarSucesion(List.of(new SolicitudReasignacion(idTarea,
                        e.candidato().idUsuario()))));

        assertThat(completada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_COMPLETADA);
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COLABORADOR");

        for (String accion : List.of("COMPLETAR_SUCESION", "CAMBIAR_ROL_INTEGRANTE", "REASIGNAR_TAREA")) {
            List<Map<String, Object>> eventos = eventos(e.idGrupo(), accion);
            assertThat(eventos).as(accion).isNotEmpty();
            for (Map<String, Object> evento : eventos) {
                assertThat(((Number) evento.get("ID_USUARIO_ACTOR")).longValue()).as(accion)
                        .isEqualTo(administrador.idUsuario());
                assertThat(detalles(evento)).as(accion).containsEntry("accesoAdministrador", true);
            }
        }
    }

    @Test
    void alertasAbiertasNoSeCierran() {
        Escenario e = escenario();
        long idTarea = tareaAsignada(e, e.saliente().idUsuario(), AHORA.minusHours(2));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(List.of(
                new SolicitudReasignacion(idTarea, e.candidato().idUsuario()))));

        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("COMPLETADA");
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    // ------------------------------------------------------------------
    // Condiciones y reasignaciones inválidas (reglas 21.5, 21.6 y 22)
    // ------------------------------------------------------------------

    @Test
    void condicionesSinResolverDa422YNoCambiaNada() throws Exception {
        Escenario e = escenario();
        long idTareaUno = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idTareaDos = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(2));
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTareaUno, e.candidato().idUsuario())))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Quedan 1 tareas por resolver o reasignar");

        // La reasignación del intento se revirtió con todo lo demás.
        assertThat(asignaciones(idTareaUno)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        assertThat(asignaciones(idTareaDos)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        assertThat(eventos(e.idGrupo(), "REASIGNAR_TAREA")).isEmpty();
        exigirSinCambios(e, idPropuesta);

        List<Map<String, Object>> rechazos = eventos(e.idGrupo(), "COMPLETAR_SUCESION");
        assertThat(rechazos).hasSize(1);
        assertThat(rechazos.get(0).get("RESULTADO")).isEqualTo("RECHAZADA");
        assertThat(rechazos.get(0).get("ID_ENTIDAD")).isEqualTo(String.valueOf(idPropuesta));
        assertThat(detalles(rechazos.get(0))).containsEntry("motivo", "Quedan 1 tareas por resolver o reasignar");
    }

    @Test
    void reasignacionATareaAjenaDa422() {
        Escenario e = escenario();
        UsuarioAlto colaborador = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        long idTareaAjena = tareaAsignada(e, colaborador.idUsuario(), AHORA.plusHours(1));
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTareaAjena, e.candidato().idUsuario())))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La tarea " + idTareaAjena + " no es una condición pendiente de la sucesión");

        assertThat(asignaciones(idTareaAjena)).containsExactly(List.of(colaborador.idUsuario(), "ACTIVO"));
        exigirSinCambios(e, idPropuesta);
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1);
    }

    @Test
    void reasignacionAlSalienteDa422() {
        Escenario e = escenario();
        long idTarea = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTarea, e.saliente().idUsuario())))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioCompletarSucesion.MENSAJE_REASIGNAR_AL_SALIENTE);

        assertThat(asignaciones(idTarea)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        exigirSinCambios(e, idPropuesta);
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1);
    }

    @Test
    void destinoInactivoAMitadDeListaRevierteTodo() {
        Escenario e = escenario();
        UsuarioAlto inactivo = crearUsuarioAlto(false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), inactivo.idUsuario(), "COLABORADOR", "INACTIVO");
        long idTareaUno = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idTareaDos = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(2));
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTareaUno, e.candidato().idUsuario()),
                        new SolicitudReasignacion(idTareaDos, inactivo.idUsuario())))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El responsable debe ser integrante activo del grupo");

        // La primera reasignación (válida y ya aplicada en el intento) también se revirtió.
        assertThat(asignaciones(idTareaUno)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        assertThat(asignaciones(idTareaDos)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        assertThat(eventos(e.idGrupo(), "REASIGNAR_TAREA")).isEmpty();
        exigirSinCambios(e, idPropuesta);
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Fallo en la transferencia (regla 22)
    // ------------------------------------------------------------------

    @Test
    void falloDuranteLaTransferenciaNoDejaSinCoordinador() {
        Escenario e = escenario();
        long idTarea = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idPropuesta = propuestaAceptada(e);
        // Falla el último paso (auditar COMPLETAR_SUCESION), cuando los roles ya cambiaron en la transacción.
        doThrow(new RuntimeException("Fallo simulado de auditoría")).when(servicioAuditoria)
                .registrar(argThat(evento -> evento != null
                        && CodigosSucesion.ACCION_COMPLETAR_SUCESION.equals(evento.codigoAccion())));
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTarea, e.candidato().idUsuario())))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Fallo simulado de auditoría");

        exigirSinCambios(e, idPropuesta);
        assertThat(asignaciones(idTarea)).containsExactly(List.of(e.saliente().idUsuario(), "ACTIVO"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO i JOIN ROL_GRUPO r ON r.ID_ROL = i.ID_ROL "
                        + "WHERE i.ID_GRUPO = ? AND r.NOMBRE_ROL = 'COORDINADOR' AND i.ID_ESTADO = ?",
                Long.class, e.idGrupo(), idEstado("INTEGRANTE_GRUPO", "ACTIVO"))).isGreaterThanOrEqualTo(1L);
        // Los eventos de auditoría de la transacción también se revirtieron.
        assertThat(eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE")).isEmpty();
        assertThat(eventos(e.idGrupo(), "REASIGNAR_TAREA")).isEmpty();
        assertThat(eventos(e.idGrupo(), "COMPLETAR_SUCESION")).isEmpty();

        // Minor 4 de la revisión final: la prueba mínima de TSA-13 solo tiene sentido si el fallo
        // ocurre con los roles YA escritos (sesión y BD); este InOrder lo demuestra directamente
        // sobre las llamadas al spy, en vez de inferirlo solo del estado final. Fallaría si alguien
        // moviera la auditoría de COMPLETAR_SUCESION antes de cambiar los roles (verificado
        // manualmente: ver "Corrección ronda" del informe).
        InOrder orden = inOrder(servicioAuditoria);
        orden.verify(servicioAuditoria)
                .registrar(argThat(ev -> ev != null && "REASIGNAR_TAREA".equals(ev.codigoAccion())));
        orden.verify(servicioAuditoria, times(2))
                .registrar(argThat(ev -> ev != null && "CAMBIAR_ROL_INTEGRANTE".equals(ev.codigoAccion())));
        orden.verify(servicioAuditoria).registrar(
                argThat(ev -> ev != null && CodigosSucesion.ACCION_COMPLETAR_SUCESION.equals(ev.codigoAccion())));
        // Un RuntimeException inesperado (no ExcepcionConflicto/ExcepcionReglaNegocio) no se audita
        // como rechazo: auditarRechazo (y su REQUIRES_NEW) nunca se invoca en este camino.
        verify(servicioAuditoria, never()).registrarEnTransaccionPropia(any());
    }

    // ------------------------------------------------------------------
    // Revalidación (regla 21.4)
    // ------------------------------------------------------------------

    @Test
    void candidatoDesactivadoAntesDeCompletarDa422SinCambios() {
        Escenario e = escenario();
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());
        servicioIntegrantes.desactivar(e.idGrupo(), e.idIntegranteCandidato());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioCompletarSucesion.MENSAJE_CANDIDATO_YA_NO_ACTIVO);

        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("ACEPTADA");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");
        assertThat(estadoIntegrante(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("INACTIVO");
        assertThat(eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE")).isEmpty();
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1);
    }

    @Test
    void candidatoConCuentaInactivaDa422() {
        Escenario e = escenario();
        long idPropuesta = propuestaAceptada(e);
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?", idEstado("USUARIO", "INACTIVO"),
                e.candidato().idUsuario());
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioCompletarSucesion.MENSAJE_CANDIDATO_YA_NO_ACTIVO);
        exigirSinCambios(e, idPropuesta);
    }

    @Test
    void salienteDegradadoAntesDeCompletarDa422() {
        Escenario e = escenario();
        UsuarioAlto otroCoordinador = crearIntegranteAlto(e.idGrupo(), "COORDINADOR");
        long idPropuesta = propuestaAceptada(e);
        autenticar(otroCoordinador.uid());
        servicioIntegrantes.cambiarRol(e.idGrupo(), e.idIntegranteSaliente(), idRol("COLABORADOR"));
        long eventosDeRolPrevios = eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE").size();

        autenticar(e.saliente().uid());
        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioCompletarSucesion.MENSAJE_SALIENTE_YA_NO_COORDINADOR);

        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("ACEPTADA");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");
        assertThat(rolDe(e.idGrupo(), otroCoordinador.idUsuario())).isEqualTo("COORDINADOR");
        assertThat(eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE")).hasSize((int) eventosDeRolPrevios);
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1);
    }

    @Test
    void candidatoYaCoordinadorPorOtraViaDa422() {
        Escenario e = escenario();
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());
        servicioIntegrantes.cambiarRol(e.idGrupo(), e.idIntegranteCandidato(), idRol("COORDINADOR"));

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CANDIDATO_YA_COORDINADOR);

        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("ACEPTADA");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COORDINADOR");
    }

    // ------------------------------------------------------------------
    // Estado y permisos (reglas 16 y 22)
    // ------------------------------------------------------------------

    @Test
    void completarPendienteDa409() throws Exception {
        Escenario e = escenario();
        autenticar(e.saliente().uid());
        long idPropuesta = servicioSucesion.proponer(e.idGrupo(), e.candidato().idUsuario()).idPropuesta();

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(null)))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);

        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("PENDIENTE");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COORDINADOR");
        List<Map<String, Object>> rechazos = eventos(e.idGrupo(), "COMPLETAR_SUCESION");
        assertThat(rechazos).hasSize(1);
        assertThat(rechazos.get(0).get("RESULTADO")).isEqualTo("RECHAZADA");
        assertThat(detalles(rechazos.get(0))).containsEntry("motivo", ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);
    }

    @Test
    void completarPorTerceroDa403() {
        Escenario e = escenario();
        UsuarioAlto colaborador = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        long idPropuesta = propuestaAceptada(e);

        for (UsuarioAlto tercero : List.of(colaborador, e.candidato())) {
            autenticar(tercero.uid());
            assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta,
                    new SolicitudCompletarSucesion(null)))
                    .isInstanceOf(ExcepcionAccesoDenegado.class)
                    .hasMessage(ServicioCompletarSucesion.MENSAJE_SOLO_SALIENTE_COMPLETA);
        }
        exigirSinCambios(e, idPropuesta);

        // Minor 1 de la revisión final: ambos 403 se auditan como RECHAZADA con motivo en código.
        List<Map<String, Object>> rechazados = eventos(e.idGrupo(), "COMPLETAR_SUCESION").stream()
                .filter(evento -> "RECHAZADA".equals(evento.get("RESULTADO"))).toList();
        assertThat(rechazados).hasSize(2);
        assertThat(rechazados).allSatisfy(evento -> assertThat(detalles(evento).get("motivo"))
                .isEqualTo("NO_ES_SALIENTE"));
    }

    // ------------------------------------------------------------------
    // Orden de reasignaciones (minor 3) y robustez de la auditoría del rechazo (minor 6/7)
    // ------------------------------------------------------------------

    /**
     * Minor 3 de la revisión final: las reasignaciones se aplican en orden ascendente de
     * {@code idTarea} (el mismo orden de bloqueo de {@code ServicioRecalculoTareas}), nunca en el
     * orden en que llegan en la solicitud. Enviarlas en orden descendente debe aplicarlas todas
     * igual.
     */
    @Test
    void reasignacionesDesordenadasPorIdTareaAplicanTodas() throws Exception {
        Escenario e = escenario();
        long idTareaMenor = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        long idTareaMayor = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(2));
        assertThat(idTareaMayor).isGreaterThan(idTareaMenor);
        long idPropuesta = propuestaAceptada(e);
        autenticar(e.saliente().uid());

        // Orden DESCENDENTE de idTarea en la solicitud.
        RespuestaPropuesta completada = servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTareaMayor, e.candidato().idUsuario()),
                        new SolicitudReasignacion(idTareaMenor, e.candidato().idUsuario()))));

        assertThat(completada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_COMPLETADA);
        assertThat(asignaciones(idTareaMenor)).containsExactly(
                List.of(e.saliente().idUsuario(), "REEMPLAZADA"),
                List.of(e.candidato().idUsuario(), "ACTIVO"));
        assertThat(asignaciones(idTareaMayor)).containsExactly(
                List.of(e.saliente().idUsuario(), "REEMPLAZADA"),
                List.of(e.candidato().idUsuario(), "ACTIVO"));
        assertThat(eventos(e.idGrupo(), "REASIGNAR_TAREA")).hasSize(2);
    }

    /**
     * Minor 6/7 de la revisión final: si falla el registro del rechazo mismo
     * ({@code registrarEnTransaccionPropia}), la excepción 409/422 original se conserva (no la
     * reemplaza un error de infraestructura de auditoría); el fallo del registro queda como
     * {@code addSuppressed}, el mismo patrón que {@code alertas.CorridaAlertas}.
     */
    @Test
    void fallaAlAuditarElRechazoNoReemplazaLaExcepcionOriginal() {
        Escenario e = escenario();
        long idTareaUno = tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(1));
        tareaAsignada(e, e.saliente().idUsuario(), AHORA.plusHours(2));
        long idPropuesta = propuestaAceptada(e);
        doThrow(new RuntimeException("Fallo simulado al auditar el rechazo")).when(servicioAuditoria)
                .registrarEnTransaccionPropia(argThat(evento -> evento != null
                        && CodigosSucesion.ACCION_COMPLETAR_SUCESION.equals(evento.codigoAccion())));
        autenticar(e.saliente().uid());

        assertThatThrownBy(() -> servicio.completar(e.idGrupo(), idPropuesta, new SolicitudCompletarSucesion(
                List.of(new SolicitudReasignacion(idTareaUno, e.candidato().idUsuario())))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Quedan 1 tareas por resolver o reasignar")
                .satisfies(excepcion -> {
                    assertThat(excepcion.getSuppressed()).hasSize(1);
                    assertThat(excepcion.getSuppressed()[0])
                            .hasMessageContaining("Fallo simulado al auditar el rechazo");
                });

        exigirSinCambios(e, idPropuesta);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private record UsuarioAlto(String uid, long idUsuario) {
    }

    private record Escenario(UsuarioAlto saliente, long idIntegranteSaliente, UsuarioAlto candidato,
            long idIntegranteCandidato, long idGrupo, long idAdulto) {
    }

    /** Grupo con un coordinador (el saliente) y un colaborador (el candidato), ambos ACTIVO, y un adulto. */
    private Escenario escenario() {
        UsuarioAlto saliente = crearUsuarioAlto(false);
        long idGrupo = fixturaGrupos.crearGrupo(saliente.idUsuario());
        long idIntegranteSaliente = fixturaGrupos.agregarIntegrante(idGrupo, saliente.idUsuario(), "COORDINADOR",
                "ACTIVO");
        UsuarioAlto candidato = crearUsuarioAlto(false);
        long idIntegranteCandidato = fixturaGrupos.agregarIntegrante(idGrupo, candidato.idUsuario(), "COLABORADOR",
                "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, saliente.idUsuario(), "ACTIVO");
        return new Escenario(saliente, idIntegranteSaliente, candidato, idIntegranteCandidato, idGrupo, idAdulto);
    }

    /** El saliente propone al candidato y este acepta: la propuesta queda ACEPTADA (sin autenticación al final). */
    private long propuestaAceptada(Escenario e) {
        autenticar(e.saliente().uid());
        long idPropuesta = servicioSucesion.proponer(e.idGrupo(), e.candidato().idUsuario()).idPropuesta();
        autenticar(e.candidato().uid());
        servicioSucesion.aceptar(e.idGrupo(), idPropuesta);
        SecurityContextHolder.clearContext();
        return idPropuesta;
    }

    /** La propuesta sigue ACEPTADA, los roles intactos y no hay cambios de rol auditados. */
    private void exigirSinCambios(Escenario e, long idPropuesta) {
        assertThat(estadoPropuesta(idPropuesta)).isEqualTo("ACEPTADA");
        assertThat(rolDe(e.idGrupo(), e.saliente().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PROPUESTA_SUCESION WHERE ID_PROPUESTA = ? AND FECHA_COMPLETADO IS NULL",
                Long.class, idPropuesta)).isEqualTo(1L);
        assertThat(eventos(e.idGrupo(), "CAMBIAR_ROL_INTEGRANTE")).isEmpty();
        assertThat(contarEventos(e.idGrupo(), "COMPLETAR_SUCESION", "EXITO")).isZero();
    }

    private UsuarioAlto crearIntegranteAlto(long idGrupo, String rol) {
        UsuarioAlto usuario = crearUsuarioAlto(false);
        fixturaGrupos.agregarIntegrante(idGrupo, usuario.idUsuario(), rol, "ACTIVO");
        return usuario;
    }

    /** USUARIO ACTIVO con id explícito &gt; 127. */
    private UsuarioAlto crearUsuarioAlto(boolean administrador) {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        String uid = FixturaGrupos.uidUnico();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                idUsuario, uid, "fixtura-" + UUID.randomUUID() + "@sucesion.mejorvejez.test",
                "Usuario completar " + idUsuario, administrador ? "S" : "N", idEstado("USUARIO", "ACTIVO"));
        return new UsuarioAlto(uid, idUsuario);
    }

    /** Tarea manual PENDIENTE del adulto del escenario, con asignación ACTIVO a {@code idAsignado}. */
    private long tareaAsignada(Escenario e, long idAsignado, OffsetDateTime horario) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo(), idAsignado, e.saliente().idUsuario());
        return idTarea;
    }

    /** {@code [idUsuarioAsignado, estado]} de cada asignación de la tarea, por {@code ID_ASIGNACION}. */
    private List<List<Object>> asignaciones(long idTarea) {
        return jdbcTemplate.query(
                "SELECT a.ID_USUARIO_ASIGNADO, ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO WHERE a.ID_TAREA = ? "
                        + "ORDER BY a.ID_ASIGNACION",
                (fila, numero) -> List.<Object>of(fila.getLong(1), fila.getString(2)), idTarea);
    }

    private String motivoAsignacionActiva(long idTarea) {
        return jdbcTemplate.queryForObject(
                "SELECT a.MOTIVO FROM ASIGNACION_TAREA a JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO "
                        + "WHERE a.ID_TAREA = ? AND ce.CODIGO_ESTADO = 'ACTIVO'",
                String.class, idTarea);
    }

    private String estadoPropuesta(long idPropuesta) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM PROPUESTA_SUCESION p "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = p.ID_ESTADO WHERE p.ID_PROPUESTA = ?",
                String.class, idPropuesta);
    }

    private String rolDe(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT r.NOMBRE_ROL FROM INTEGRANTE_GRUPO i JOIN ROL_GRUPO r ON r.ID_ROL = i.ID_ROL "
                        + "WHERE i.ID_GRUPO = ? AND i.ID_USUARIO = ?",
                String.class, idGrupo, idUsuario);
    }

    private String estadoIntegrante(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM INTEGRANTE_GRUPO i JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = i.ID_ESTADO "
                        + "WHERE i.ID_GRUPO = ? AND i.ID_USUARIO = ?",
                String.class, idGrupo, idUsuario);
    }

    private long idRol(String nombreRol) {
        return jdbcTemplate.queryForObject("SELECT ID_ROL FROM ROL_GRUPO WHERE NOMBRE_ROL = ?", Long.class,
                nombreRol);
    }

    /** Eventos del grupo con la acción, con el código del resultado en {@code RESULTADO}, por id. */
    private List<Map<String, Object>> eventos(long idGrupo, String codigoAccion) {
        return jdbcTemplate.queryForList(
                "SELECT e.NOMBRE_SERVICIO, e.TIPO_ENTIDAD, e.ID_ENTIDAD, e.ID_USUARIO_ACTOR, e.DETALLES_JSON, "
                        + "ce.CODIGO_ESTADO AS RESULTADO FROM EVENTO_AUDITORIA e "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = e.ID_RESULTADO "
                        + "WHERE e.ID_GRUPO = ? AND e.CODIGO_ACCION = ? ORDER BY e.ID_EVENTO_AUDITORIA",
                idGrupo, codigoAccion);
    }

    private long contarEventos(long idGrupo, String codigoAccion, String resultado) {
        return eventos(idGrupo, codigoAccion).stream().filter(evento -> resultado.equals(evento.get("RESULTADO")))
                .count();
    }

    private Map<String, Object> eventoDeEntidad(List<Map<String, Object>> eventos, long idEntidad) {
        return eventos.stream().filter(evento -> String.valueOf(idEntidad).equals(evento.get("ID_ENTIDAD")))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> detalles(Map<String, Object> evento) {
        return jsonMapper.readValue((String) evento.get("DETALLES_JSON"), new TypeReference<Map<String, Object>>() {
        });
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
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
