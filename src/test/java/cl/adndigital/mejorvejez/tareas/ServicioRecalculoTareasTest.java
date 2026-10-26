package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import cl.adndigital.mejorvejez.adultos.ServicioAdultosMayores;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.planificacion.RespuestaDetallePlan;
import cl.adndigital.mejorvejez.planificacion.ServicioDetallesPlan;
import cl.adndigital.mejorvejez.planificacion.ServicioPlanes;
import cl.adndigital.mejorvejez.planificacion.SolicitudActualizarPlan;
import cl.adndigital.mejorvejez.planificacion.SolicitudGuardarDetalle;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.Escenario;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.UsuarioAlto;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba el recálculo síncrono de tareas de medicamentos ante cambios de
 * plan, detalle o adulto mayor (HU-05/HU-06, regla 8 de la Fase 4) contra
 * Oracle real, con el reloj fijo en el lunes 2026-11-02 10:00
 * (America/Santiago). Los cambios se hacen con los servicios reales de F2 y
 * F3, autenticado como coordinador (con id > 127, porque se compara como
 * actor del historial). Cada prueba crea su propio grupo, adulto, plan y
 * detalle, y solo afirma sobre sus propios ids.
 *
 * <p>{@link RepositorioHistorialEstadoTarea} es un spy: delega en el bean
 * real salvo en {@link #fallaDelRecalculoRevierteElCambioQueLoOrigino}.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioRecalculoTareasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final OffsetDateTime INICIO = ZonedDateTime.of(HOY, LocalTime.MIDNIGHT, ZonaNegocio.ZONA)
            .toOffsetDateTime();
    private static final OffsetDateTime FIN = ZonedDateTime.of(HOY.plusDays(7), LocalTime.MIDNIGHT,
            ZonaNegocio.ZONA).toOffsetDateTime();
    private static final String MOTIVO = "Cancelada por un cambio en el plan de medicamentos";
    private static final String HORARIO_08_20 = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\",\"20:00\"]}";
    private static final String HORARIO_09 = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"09:00\"]}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioDetallesPlan servicioDetalles;

    @Autowired
    private ServicioPlanes servicioPlanes;

    @Autowired
    private ServicioAdultosMayores servicioAdultos;

    @Autowired
    private ServicioGeneracionTareas servicioGeneracion;

    @MockitoSpyBean
    private RepositorioHistorialEstadoTarea repositorioHistorial;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGeneracion fixtura;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;

    @BeforeEach
    void prepararFixturas() {
        fixtura = new FixturaGeneracion(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Generación inmediata y cambio de horario
    // ------------------------------------------------------------------

    @Test
    void crearDetalleGeneraLasTareasFuturasAlInstante() {
        Contexto c = contexto();
        long idResponsable = fixtura.crearResponsable(c.e.idGrupo(), "ACTIVO");
        Datos datos = new Datos(c);
        datos.idResponsable = idResponsable;

        long idDetalle = crear(c, datos);

        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        assertThat(tareas).hasSize(13);
        assertThat(tareas).allSatisfy(t -> assertThat(t.get("CODIGO_ESTADO")).isEqualTo("PENDIENTE"));
        List<OffsetDateTime> horarios = fixtura.horariosTareas(idDetalle);
        assertThat(horarios.get(0)).isAtSameInstantAs(t("2026-11-02T20:00-03:00"));
        assertThat(horarios.get(12)).isAtSameInstantAs(t("2026-11-08T20:00-03:00"));
        List<Map<String, Object>> ocurrencias = fixtura.ocurrencias(idDetalle);
        assertThat(ocurrencias).hasSize(13);
        for (int i = 0; i < tareas.size(); i++) {
            long idTarea = numero(tareas.get(i), "ID_TAREA");
            assertThat(tareas.get(i).get("CLAVE_OCURRENCIA"))
                    .isEqualTo(PlanificadorOcurrencias.clave(idDetalle, horarios.get(i), 1));
            assertThat(ocurrencias.get(i).get("CODIGO_ESTADO")).isEqualTo("TAREA_CREADA");
            assertThat(numero(ocurrencias.get(i), "ID_TAREA")).isEqualTo(idTarea);
            List<Map<String, Object>> asignaciones = fixtura.asignaciones(idTarea);
            assertThat(asignaciones).hasSize(1);
            assertThat(numero(asignaciones.get(0), "ID_USUARIO_ASIGNADO")).isEqualTo(idResponsable);
            assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
            // Regla 9: crear una tarea no escribe historial.
            assertThat(fixtura.contarHistorial(idTarea)).isZero();
        }
    }

    @Test
    void cambioDeHorarioCancelaFuturasPendientesYGeneraNuevas() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        datos.idResponsable = fixtura.crearResponsable(c.e.idGrupo(), "ACTIVO");
        long idDetalle = crear(c, datos);
        List<Long> previas = idsTareas(idDetalle);
        assertThat(previas).hasSize(13);

        datos.horario = HORARIO_09;
        actualizar(c, idDetalle, datos);

        for (long idTarea : previas) {
            Map<String, Object> tarea = tarea(idTarea);
            assertThat(tarea.get("CODIGO_ESTADO")).isEqualTo("CANCELADA");
            assertThat(tarea.get("MOTIVO_CANCELACION")).isEqualTo(MOTIVO);

            List<Map<String, Object>> historial = historial(idTarea);
            assertThat(historial).hasSize(1);
            assertThat(numero(historial.get(0), "ID_ESTADO_ANTERIOR")).isEqualTo(idEstadoTarea("PENDIENTE"));
            assertThat(numero(historial.get(0), "ID_ESTADO_NUEVO")).isEqualTo(idEstadoTarea("CANCELADA"));
            assertThat(c.actor.idUsuario()).isGreaterThan(127L);
            assertThat(numero(historial.get(0), "ID_USUARIO_CAMBIO")).isEqualTo(c.actor.idUsuario());

            List<Map<String, Object>> asignaciones = asignacionesConTermino(idTarea);
            assertThat(asignaciones).hasSize(1);
            assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("CANCELADA");
            assertThat(asignaciones.get(0).get("FECHA_TERMINO")).isNotNull();

            assertThat(estadoOcurrenciaDeTarea(idTarea)).isEqualTo("TAREA_CREADA");

            List<Map<String, Object>> eventos = eventosCancelacion(idTarea);
            assertThat(eventos).hasSize(1);
            assertThat(numero(eventos.get(0), "ID_USUARIO_ACTOR")).isEqualTo(c.actor.idUsuario());
            assertThat(numero(eventos.get(0), "ID_GRUPO")).isEqualTo(c.e.idGrupo());
            // Un coordinador (integrante) no deja la marca de administrador.
            assertThat(leerJson((String) eventos.get(0).get("DETALLES_JSON")))
                    .doesNotContainKey("accesoAdministrador")
                    .containsEntry("idDetallePlan", (int) idDetalle);
        }

        List<Map<String, Object>> vigentes = noCanceladas(idDetalle);
        assertThat(vigentes).hasSize(6);
        List<OffsetDateTime> esperados = new ArrayList<>();
        for (int dia = 1; dia <= 6; dia++) {
            esperados.add(t("2026-11-02T09:00-03:00").plusDays(dia));
        }
        assertMismosInstantes(vigentes.stream().map(v -> (OffsetDateTime) v.get("FECHA_HORARIO")).toList(),
                esperados);
        assertThat(vigentes).allSatisfy(v -> assertThat(v.get("CODIGO_ESTADO")).isEqualTo("PENDIENTE"));
    }

    @Test
    void administradorSinMembresiaQuedaTrazadoEnLasCancelaciones() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        List<Long> previas = idsTareas(idDetalle);
        String uidAdministrador = fixtura.crearUsuario(true);
        long idAdministrador = jdbcTemplate.queryForObject(
                "SELECT ID_USUARIO FROM USUARIO WHERE UID_FIREBASE = ?", Long.class, uidAdministrador);
        autenticar(uidAdministrador);

        datos.horario = HORARIO_09;
        actualizar(c, idDetalle, datos);

        assertThat(previas).hasSize(13);
        for (long idTarea : previas) {
            List<Map<String, Object>> eventos = eventosCancelacion(idTarea);
            assertThat(eventos).hasSize(1);
            assertThat(numero(eventos.get(0), "ID_USUARIO_ACTOR")).isEqualTo(idAdministrador);
            assertThat(leerJson((String) eventos.get(0).get("DETALLES_JSON")))
                    .containsEntry("accesoAdministrador", true);
            assertThat(numero(historial(idTarea).get(0), "ID_USUARIO_CAMBIO")).isEqualTo(idAdministrador);
        }
    }

    @Test
    void ocurrenciasPlanificadaYErrorFuturasQueYaNoCorrespondenSeCancelan() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        // Turnos de 14:00 sin tarea (fase A reservada o fase B fallida): ya no corresponden tras el cambio.
        OffsetDateTime planificada = t("2026-11-05T14:00-03:00");
        OffsetDateTime enError = t("2026-11-06T14:00-03:00");
        long idPlanificada = fixturaTareas.crearOcurrencia(idDetalle, c.e.idAdulto(), c.e.idGrupo(), planificada,
                PlanificadorOcurrencias.clave(idDetalle, planificada, 1), "PLANIFICADA", null);
        long idError = fixturaTareas.crearOcurrencia(idDetalle, c.e.idAdulto(), c.e.idGrupo(), enError,
                PlanificadorOcurrencias.clave(idDetalle, enError, 1), "ERROR", null);

        datos.horario = HORARIO_09;
        actualizar(c, idDetalle, datos);

        for (long idOcurrencia : List.of(idPlanificada, idError)) {
            Map<String, Object> fila = jdbcTemplate.queryForMap(
                    "SELECT o.ID_TAREA, ce.CODIGO_ESTADO FROM OCURRENCIA_PLANIFICADA o "
                            + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = o.ID_ESTADO WHERE o.ID_OCURRENCIA = ?",
                    idOcurrencia);
            assertThat(fila.get("CODIGO_ESTADO")).isEqualTo("CANCELADA");
            assertThat(fila.get("ID_TAREA")).isNull();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM TAREA_CUIDADO WHERE ID_DETALLE_PLAN = ? AND FECHA_HORARIO IN (?, ?)",
                Long.class, idDetalle, planificada, enError)).isZero();
        assertThat(noCanceladas(idDetalle)).hasSize(6);
    }

    @Test
    void tareaEnCursoNoSeModifica() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        datos.idResponsable = fixtura.crearResponsable(c.e.idGrupo(), "ACTIVO");
        long idDetalle = crear(c, datos);
        long idEnCurso = idTareaEn(idDetalle, t("2026-11-03T08:00-03:00"));
        cambiarEstadoTarea(idEnCurso, "EN_CURSO");
        Map<String, Object> antes = tarea(idEnCurso);

        datos.horario = HORARIO_09;
        datos.concentracion = "1 g";
        actualizar(c, idDetalle, datos);

        Map<String, Object> despues = tarea(idEnCurso);
        assertThat(despues.get("CODIGO_ESTADO")).isEqualTo("EN_CURSO");
        assertThat(despues.get("TITULO_TAREA")).isEqualTo(antes.get("TITULO_TAREA"));
        assertThat(despues).isEqualTo(antes);
        assertThat(fixtura.contarHistorial(idEnCurso)).isZero();
        assertThat(fixtura.asignaciones(idEnCurso)).singleElement()
                .satisfies(a -> assertThat(a.get("CODIGO_ESTADO")).isEqualTo("ACTIVO"));
        assertThat(eventosCancelacion(idEnCurso)).isEmpty();
    }

    @Test
    void tareasPasadasYFinalizadasNoSeTocan() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        long idPasada = tareaPasadaDeHoyALas8(c, idDetalle);
        long idCompletada = idTareaEn(idDetalle, t("2026-11-03T20:00-03:00"));
        cambiarEstadoTarea(idCompletada, "COMPLETADA");
        long idNoRealizada = idTareaEn(idDetalle, t("2026-11-04T08:00-03:00"));
        cambiarEstadoTarea(idNoRealizada, "NO_REALIZADA");
        Map<Long, Map<String, Object>> antes = Map.of(idPasada, tarea(idPasada), idCompletada,
                tarea(idCompletada), idNoRealizada, tarea(idNoRealizada));

        datos.horario = HORARIO_09;
        datos.dosis = new BigDecimal("2");
        actualizar(c, idDetalle, datos);

        antes.forEach((idTarea, fila) -> {
            assertThat(tarea(idTarea)).isEqualTo(fila);
            assertThat(fixtura.contarHistorial(idTarea)).isZero();
        });
        assertThat(tarea(idPasada).get("CODIGO_ESTADO")).isEqualTo("PENDIENTE");
    }

    @Test
    void volverAlHorarioAnteriorRegeneraConNuevaRevision() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);

        datos.horario = HORARIO_09;
        actualizar(c, idDetalle, datos);
        datos.horario = HORARIO_08_20;
        actualizar(c, idDetalle, datos);

        OffsetDateTime martes8 = t("2026-11-03T08:00-03:00");
        List<Map<String, Object>> delTurno = fixtura.tareas(idDetalle).stream()
                .filter(t -> ((String) t.get("CLAVE_OCURRENCIA"))
                        .startsWith("DET-" + idDetalle + "-202611031100-"))
                .toList();
        assertThat(delTurno).hasSize(2);
        Map<Object, Object> estadoPorClave = delTurno.stream()
                .collect(Collectors.toMap(t -> t.get("CLAVE_OCURRENCIA"), t -> t.get("CODIGO_ESTADO")));
        assertThat(estadoPorClave).containsEntry(PlanificadorOcurrencias.clave(idDetalle, martes8, 1), "CANCELADA")
                .containsEntry(PlanificadorOcurrencias.clave(idDetalle, martes8, 2), "PENDIENTE");

        // 13 de 08:00/20:00 (revisión 2) vigentes; las de 09:00 quedaron canceladas.
        assertThat(noCanceladas(idDetalle)).hasSize(13);
        assertThat(fixture09Canceladas(idDetalle)).hasSize(6);
    }

    @Test
    void desactivarYReactivarDetalle() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);

        servicioDetalles.desactivar(c.e.idGrupo(), c.e.idPlan(), idDetalle);

        assertThat(fixtura.tareas(idDetalle)).hasSize(13)
                .allSatisfy(t -> assertThat(t.get("CODIGO_ESTADO")).isEqualTo("CANCELADA"));

        servicioDetalles.reactivar(c.e.idGrupo(), c.e.idPlan(), idDetalle);

        List<Map<String, Object>> vigentes = noCanceladas(idDetalle);
        assertThat(vigentes).hasSize(13)
                .allSatisfy(t -> assertThat((String) t.get("CLAVE_OCURRENCIA")).endsWith("-2"));
        assertThat(fixtura.tareas(idDetalle)).hasSize(26);
    }

    @Test
    void acotarVigenciaDelPlanCancelaLasQueQuedanFuera() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);

        datos.fechaFin = LocalDate.of(2026, 11, 4);
        actualizar(c, idDetalle, datos);
        servicioPlanes.actualizar(c.e.idGrupo(), c.e.idPlan(),
                new SolicitudActualizarPlan("Plan fixtura", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4)));

        OffsetDateTime limite = t("2026-11-05T00:00-03:00");
        for (Map<String, Object> tarea : tareasConHorario(idDetalle)) {
            OffsetDateTime horario = (OffsetDateTime) tarea.get("FECHA_HORARIO");
            String esperado = horario.isBefore(limite) ? "PENDIENTE" : "CANCELADA";
            assertThat(tarea.get("CODIGO_ESTADO")).as("tarea de %s", horario).isEqualTo(esperado);
        }
        assertThat(noCanceladas(idDetalle)).hasSize(5);
        assertThat(fixtura.tareas(idDetalle)).hasSize(13);
    }

    @Test
    void desactivarPlanYAdultoCancelanFuturas() {
        Contexto conPlan = contexto();
        long idDetallePlan = crear(conPlan, new Datos(conPlan));
        long idPasada = tareaPasadaDeHoyALas8(conPlan, idDetallePlan);

        servicioPlanes.desactivar(conPlan.e.idGrupo(), conPlan.e.idPlan());

        assertThat(noCanceladas(idDetallePlan)).extracting(t -> numero(t, "ID_TAREA")).containsExactly(idPasada);
        assertThat(fixtura.tareas(idDetallePlan)).hasSize(14);

        Contexto conAdulto = contexto();
        long idDetalleUno = crear(conAdulto, new Datos(conAdulto));
        long idOtroPlan = fixturaPlanes.crearPlanConVigencia(conAdulto.e.idGrupo(), conAdulto.e.idAdulto(),
                conAdulto.e.idCoordinador(), LocalDate.of(2026, 11, 1), null);
        Datos datosOtro = new Datos(conAdulto);
        datosOtro.horario = HORARIO_09;
        long idDetalleOtro = servicioDetalles.crear(conAdulto.e.idGrupo(), idOtroPlan, datosOtro.solicitud())
                .idDetallePlan();
        assertThat(fixtura.tareas(idDetalleOtro)).hasSize(6);

        servicioAdultos.desactivar(conAdulto.e.idGrupo(), conAdulto.e.idAdulto());

        assertThat(noCanceladas(idDetalleUno)).isEmpty();
        assertThat(noCanceladas(idDetalleOtro)).isEmpty();
        for (long idTarea : idsTareas(idDetalleOtro)) {
            assertThat(numero(historial(idTarea).get(0), "ID_USUARIO_CAMBIO"))
                    .isEqualTo(conAdulto.actor.idUsuario());
            assertThat(eventosCancelacion(idTarea)).hasSize(1);
        }
    }

    @Test
    void reactivarAdultoRegenera() {
        Contexto c = contexto();
        long idDetalle = crear(c, new Datos(c));
        servicioAdultos.desactivar(c.e.idGrupo(), c.e.idAdulto());
        assertThat(noCanceladas(idDetalle)).isEmpty();

        servicioAdultos.reactivar(c.e.idGrupo(), c.e.idAdulto());

        List<Map<String, Object>> vigentes = noCanceladas(idDetalle);
        assertThat(vigentes).hasSize(13)
                .allSatisfy(t -> assertThat((String) t.get("CLAVE_OCURRENCIA")).endsWith("-2"))
                .allSatisfy(t -> assertThat(t.get("CODIGO_ESTADO")).isEqualTo("PENDIENTE"));
    }

    @Test
    void reactivarPlanRegenera() {
        Contexto c = contexto();
        long idDetalle = crear(c, new Datos(c));
        servicioPlanes.desactivar(c.e.idGrupo(), c.e.idPlan());

        servicioPlanes.reactivar(c.e.idGrupo(), c.e.idPlan());

        assertThat(noCanceladas(idDetalle)).hasSize(13)
                .allSatisfy(t -> assertThat((String) t.get("CLAVE_OCURRENCIA")).endsWith("-2"));
    }

    // ------------------------------------------------------------------
    // HU-06 y ajuste de texto
    // ------------------------------------------------------------------

    @Test
    void cambiarResponsableNoModificaTareasCreadas() {
        Contexto c = contexto();
        long idResponsableA = fixtura.crearResponsable(c.e.idGrupo(), "ACTIVO");
        long idResponsableB = fixtura.crearResponsable(c.e.idGrupo(), "ACTIVO");
        Datos datos = new Datos(c);
        datos.idResponsable = idResponsableA;
        long idDetalle = crear(c, datos);
        List<Long> ids = idsTareas(idDetalle);

        datos.idResponsable = idResponsableB;
        actualizar(c, idDetalle, datos);

        assertThat(idsTareas(idDetalle)).isEqualTo(ids);
        for (long idTarea : ids) {
            assertThat(tarea(idTarea).get("CODIGO_ESTADO")).isEqualTo("PENDIENTE");
            List<Map<String, Object>> asignaciones = fixtura.asignaciones(idTarea);
            assertThat(asignaciones).hasSize(1);
            assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
            assertThat(numero(asignaciones.get(0), "ID_USUARIO_ASIGNADO")).isEqualTo(idResponsableA);
        }
    }

    @Test
    void cambiarDosisAjustaDescripcionDePendientesFuturas() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        long idPasada = tareaPasadaDeHoyALas8(c, idDetalle);
        Map<String, Object> pasadaAntes = tarea(idPasada);
        List<Long> futuras = idsTareas(idDetalle).stream().filter(id -> id != idPasada).toList();
        assertThat(tarea(futuras.get(0)).get("DESCRIPCION_TAREA")).isEqualTo("Dosis: 1 Comprimido. Con comida");

        datos.dosis = new BigDecimal("2");
        datos.instrucciones = null;
        actualizar(c, idDetalle, datos);

        assertThat(futuras).hasSize(13);
        for (long idTarea : futuras) {
            Map<String, Object> tarea = tarea(idTarea);
            assertThat(tarea.get("CODIGO_ESTADO")).isEqualTo("PENDIENTE");
            assertThat(tarea.get("DESCRIPCION_TAREA")).isEqualTo("Dosis: 2 Comprimido");
            assertThat((String) tarea.get("TITULO_TAREA")).endsWith(" 500 mg");
        }
        assertThat(tarea(idPasada)).isEqualTo(pasadaAntes);
        assertThat(noCanceladas(idDetalle)).hasSize(14);
    }

    // ------------------------------------------------------------------
    // Concurrencia, atomicidad y regla 13
    // ------------------------------------------------------------------

    @Test
    void recalculoYGeneracionConcurrentesNoDuplican() throws Exception {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        datos.horario = HORARIO_09;
        SolicitudGuardarDetalle solicitud = datos.solicitud();

        CyclicBarrier barrera = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> cambio = () -> {
                autenticar(c.actor.uid());
                try {
                    barrera.await(30, TimeUnit.SECONDS);
                    return servicioDetalles.actualizar(c.e.idGrupo(), c.e.idPlan(), idDetalle, solicitud);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            Callable<Object> generacion = () -> {
                barrera.await(30, TimeUnit.SECONDS);
                return servicioGeneracion.ejecutar(INICIO, FIN, c.e.idGrupo());
            };
            Future<Object> resultadoCambio = hilos.submit(cambio);
            Future<Object> resultadoGeneracion = hilos.submit(generacion);
            resultadoCambio.get(120, TimeUnit.SECONDS);
            RespuestaEjecucion ejecucion = (RespuestaEjecucion) resultadoGeneracion.get(120, TimeUnit.SECONDS);
            assertThat(ejecucion.cantidadErrores()).isZero();
        } finally {
            hilos.shutdownNow();
        }

        Map<Instant, Long> vigentesPorTurno = new HashMap<>();
        for (Map<String, Object> tarea : noCanceladas(idDetalle)) {
            vigentesPorTurno.merge(((OffsetDateTime) tarea.get("FECHA_HORARIO")).toInstant(), 1L, Long::sum);
        }
        assertThat(vigentesPorTurno.values()).allSatisfy(n -> assertThat(n).isEqualTo(1L));
        // Las 6 futuras de 09:00 existen siempre; ninguna futura de 08:00/20:00 sobrevive.
        OffsetDateTime ahora = t("2026-11-02T10:00-03:00");
        List<Instant> futurasVigentes = vigentesPorTurno.keySet().stream()
                .filter(instante -> instante.isAfter(ahora.toInstant())).sorted().toList();
        List<Instant> esperadas = new ArrayList<>();
        for (int dia = 1; dia <= 6; dia++) {
            esperadas.add(t("2026-11-02T09:00-03:00").plusDays(dia).toInstant());
        }
        assertThat(futurasVigentes).isEqualTo(esperadas);
        // Regla 4 (ruling final de F4): la corrida, con su ventana desde las
        // 00:00, no reserva turnos ya pasados de hoy (08:00 ni 09:00), así
        // que ninguna tarea vigente queda antes de ahora.
        assertThat(vigentesPorTurno.keySet()).noneMatch(instante -> instante.isBefore(ahora.toInstant()));
        assertThat(fixtura.tareas(idDetalle).stream().map(t -> t.get("CLAVE_OCURRENCIA")).distinct())
                .hasSize(fixtura.tareas(idDetalle).size());
    }

    /**
     * Regla 4 (ruling final de F4): la fase A solo reserva turnos con
     * instante {@code >= max(inicio de la ventana, ahora)}. Un detalle
     * 08:00/20:00 que aparece a las 10:00 (insertado por JDBC, sin pasar por
     * el recálculo) y una corrida con la ventana desde las 00:00 dejan hoy
     * solo la tarea de las 20:00: la de las 08:00 nacería ya atrasada.
     */
    @Test
    void corridaManualNoCreaTurnosPasadosDeHoy() {
        Escenario e = fixtura.escenario(LocalDate.of(2026, 11, 1), null);
        FixturaGeneracion.Detalle datos = new FixturaGeneracion.Detalle(HOY);
        datos.horas = List.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        long idDetalle = fixtura.crearDetalle(e, datos);

        RespuestaEjecucion respuesta = servicioGeneracion.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(respuesta.cantidadCreada()).isEqualTo(13);
        List<OffsetDateTime> esperados = new ArrayList<>();
        esperados.add(t("2026-11-02T20:00-03:00"));
        for (int dia = 1; dia < 7; dia++) {
            esperados.add(t("2026-11-02T08:00-03:00").plusDays(dia));
            esperados.add(t("2026-11-02T20:00-03:00").plusDays(dia));
        }
        assertMismosInstantes(fixtura.horariosTareas(idDetalle), esperados);
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(13);
    }

    @Test
    void fallaDelRecalculoRevierteElCambioQueLoOrigino() {
        Contexto c = contexto();
        Datos datos = new Datos(c);
        long idDetalle = crear(c, datos);
        List<Long> ids = idsTareas(idDetalle);
        doThrow(new IllegalStateException("falla simulada del historial")).when(repositorioHistorial).save(any());

        datos.horario = HORARIO_09;
        assertThatThrownBy(() -> actualizar(c, idDetalle, datos)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForList(
                "SELECT TO_CHAR(HORA_ADMINISTRACION, 'HH24:MI') FROM DETALLE_PLAN_HORARIO "
                        + "WHERE ID_DETALLE_PLAN = ? ORDER BY HORA_ADMINISTRACION",
                String.class, idDetalle)).containsExactly("08:00", "20:00");
        assertThat(idsTareas(idDetalle)).isEqualTo(ids);
        assertThat(noCanceladas(idDetalle)).hasSize(13);
    }

    @Test
    void eliminarDetalleConTareasGeneradasDevuelve422() {
        Contexto c = contexto();
        long idDetalle = crear(c, new Datos(c));
        assertThat(fixtura.tareas(idDetalle)).hasSize(13);

        assertThatThrownBy(() -> servicioDetalles.eliminar(c.e.idGrupo(), c.e.idPlan(), idDetalle))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento del plan tiene historial o referencias; desactívalo en su lugar");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = ?",
                Long.class, idDetalle)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    /** Escenario F1–F3 (plan vigente desde 2026-11-01, sin término) y un coordinador con id > 127, autenticado. */
    private Contexto contexto() {
        Escenario e = fixtura.escenario(LocalDate.of(2026, 11, 1), null);
        UsuarioAlto actor = fixtura.crearIntegranteConIdAlto(e.idGrupo(), "COORDINADOR", "ACTIVO");
        autenticar(actor.uid());
        return new Contexto(e, actor);
    }

    private record Contexto(Escenario e, UsuarioAlto actor) {
    }

    private long crear(Contexto c, Datos datos) {
        RespuestaDetallePlan creado = servicioDetalles.crear(c.e.idGrupo(), c.e.idPlan(), datos.solicitud());
        return creado.idDetallePlan();
    }

    private void actualizar(Contexto c, long idDetalle, Datos datos) {
        servicioDetalles.actualizar(c.e.idGrupo(), c.e.idPlan(), idDetalle, datos.solicitud());
    }

    /** Tarea PENDIENTE de hoy a las 08:00 (ya pasada a las 10:00), con su ocurrencia TAREA_CREADA (revisión 1). */
    private long tareaPasadaDeHoyALas8(Contexto c, long idDetalle) {
        OffsetDateTime horario = t("2026-11-02T08:00-03:00");
        String clave = PlanificadorOcurrencias.clave(idDetalle, horario, 1);
        long idTarea = fixturaTareas.crearTareaMedicamento(c.e.idGrupo(), c.e.idAdulto(), idDetalle, horario,
                "PENDIENTE", clave);
        fixturaTareas.crearOcurrencia(idDetalle, c.e.idAdulto(), c.e.idGrupo(), horario, clave, "TAREA_CREADA",
                idTarea);
        return idTarea;
    }

    private List<Long> idsTareas(long idDetalle) {
        return fixtura.tareas(idDetalle).stream().map(t -> numero(t, "ID_TAREA")).toList();
    }

    private long idTareaEn(long idDetalle, OffsetDateTime horario) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_TAREA FROM TAREA_CUIDADO WHERE ID_DETALLE_PLAN = ? AND FECHA_HORARIO = ?", Long.class,
                idDetalle, horario);
    }

    private void cambiarEstadoTarea(long idTarea, String codigo) {
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET ID_ESTADO = ? WHERE ID_TAREA = ?", idEstadoTarea(codigo),
                idTarea);
    }

    private Map<String, Object> tarea(long idTarea) {
        return jdbcTemplate.queryForMap(
                "SELECT ce.CODIGO_ESTADO, t.TITULO_TAREA, t.DESCRIPCION_TAREA, t.MOTIVO_CANCELACION, "
                        + "TO_CHAR(t.FECHA_ACTUALIZACION, 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM') AS FECHA_ACTUALIZACION "
                        + "FROM TAREA_CUIDADO t JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO "
                        + "WHERE t.ID_TAREA = ?",
                idTarea);
    }

    private List<Map<String, Object>> tareasConHorario(long idDetalle) {
        return jdbcTemplate.queryForList(
                "SELECT t.ID_TAREA, t.CLAVE_OCURRENCIA, t.FECHA_HORARIO, ce.CODIGO_ESTADO "
                        + "FROM TAREA_CUIDADO t JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO "
                        + "WHERE t.ID_DETALLE_PLAN = ? ORDER BY t.FECHA_HORARIO, t.ID_TAREA",
                idDetalle).stream().map(this::conHorarioOffset).toList();
    }

    private List<Map<String, Object>> noCanceladas(long idDetalle) {
        return tareasConHorario(idDetalle).stream()
                .filter(t -> !"CANCELADA".equals(t.get("CODIGO_ESTADO"))).toList();
    }

    /** Tareas de 09:00 del detalle (todas deben estar canceladas en {@link #volverAlHorarioAnteriorRegeneraConNuevaRevision}). */
    private List<Map<String, Object>> fixture09Canceladas(long idDetalle) {
        return tareasConHorario(idDetalle).stream()
                .filter(t -> ((OffsetDateTime) t.get("FECHA_HORARIO")).atZoneSameInstant(ZonaNegocio.ZONA)
                        .toLocalTime().equals(LocalTime.of(9, 0)))
                .filter(t -> "CANCELADA".equals(t.get("CODIGO_ESTADO")))
                .toList();
    }

    /** Normaliza FECHA_HORARIO a {@link OffsetDateTime} (el driver puede devolver otro tipo en queryForList). */
    private Map<String, Object> conHorarioOffset(Map<String, Object> fila) {
        Map<String, Object> copia = new HashMap<>(fila);
        copia.put("FECHA_HORARIO", jdbcTemplate.queryForObject(
                "SELECT FECHA_HORARIO FROM TAREA_CUIDADO WHERE ID_TAREA = ?", OffsetDateTime.class,
                numero(fila, "ID_TAREA")));
        return copia;
    }

    private List<Map<String, Object>> historial(long idTarea) {
        return jdbcTemplate.queryForList(
                "SELECT ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, ID_USUARIO_CAMBIO FROM HISTORIAL_ESTADO_TAREA "
                        + "WHERE ID_TAREA = ? ORDER BY ID_HISTORIAL",
                idTarea);
    }

    private List<Map<String, Object>> asignacionesConTermino(long idTarea) {
        return jdbcTemplate.queryForList(
                "SELECT a.FECHA_TERMINO, ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO WHERE a.ID_TAREA = ?",
                idTarea);
    }

    private String estadoOcurrenciaDeTarea(long idTarea) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM OCURRENCIA_PLANIFICADA o "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = o.ID_ESTADO WHERE o.ID_TAREA = ?",
                String.class, idTarea);
    }

    private List<Map<String, Object>> eventosCancelacion(long idTarea) {
        return jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, ID_GRUPO, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'TAREA_CUIDADO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = ?",
                String.valueOf(idTarea), AccionesTareas.CANCELAR_TAREA_POR_CAMBIO_PLAN);
    }

    private Map<String, Object> leerJson(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private long idEstadoTarea(String codigo) {
        return fixtura.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, codigo);
    }

    private long opcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
    }

    private static OffsetDateTime t(String texto) {
        return OffsetDateTime.parse(texto);
    }

    private static void assertMismosInstantes(List<OffsetDateTime> reales, List<OffsetDateTime> esperados) {
        assertThat(reales.stream().map(OffsetDateTime::toInstant).toList())
                .containsExactlyElementsOf(esperados.stream().map(OffsetDateTime::toInstant).toList());
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

    /** Solicitud de detalle mutable: diario, 08:00 y 20:00, dosis 1 Comprimido, desde hoy y sin término. */
    private final class Datos {
        long idMedicamento;
        String concentracion = "500 mg";
        String instrucciones = "Con comida";
        BigDecimal dosis = BigDecimal.ONE;
        List<Long> dias;
        String horario = HORARIO_08_20;
        LocalDate fechaInicio = HOY;
        LocalDate fechaFin;
        Long idResponsable;

        Datos(Contexto c) {
            this.idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
            this.dias = List.of("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO").stream()
                    .map(dia -> opcion("DIA_SEMANA", dia)).toList();
        }

        SolicitudGuardarDetalle solicitud() {
            return new SolicitudGuardarDetalle(idMedicamento, opcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO"),
                    concentracion, instrucciones, dosis, opcion("UNIDAD_DOSIS", "COMPRIMIDO"), dias,
                    JSON.readTree(horario), fechaInicio, fechaFin, null, null, "AUTOMATICO", null, idResponsable);
        }
    }
}
