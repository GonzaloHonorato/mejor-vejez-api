package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.Detalle;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.Escenario;

/**
 * Prueba el generador diario de tareas de medicamentos (HU-05, reglas 1–9 de
 * la Fase 4) contra Oracle real, con el reloj fijo en el lunes 2026-11-02
 * 00:05 (America/Santiago). Cada prueba crea su propio grupo, adulto, plan y
 * detalle y corre {@code ejecutar(..., idGrupo)}: la BD es compartida, así
 * que solo {@link #corridaGlobalProcesaElGrupoDeLaPrueba} (y la corrida
 * manual del administrador, que por diseño es global) recorren todos los
 * grupos, y aun así solo afirman sobre sus propios datos.
 *
 * <p>{@link RepositorioTareaCuidado}, {@link RepositorioConsultaGeneracion}
 * y {@link RepositorioOcurrenciaPlanificada} son spies ({@code
 * @MockitoSpyBean}): delegan en el bean real salvo donde una prueba les
 * programa otra respuesta, y Spring los reinicia después de cada prueba. El
 * primero inyecta el fallo de {@link #reintentoTrasFalloSinDuplicar}; los
 * otros dos dejan vacía la corrida manual (global por diseño) de {@link
 * #generarManualExigeAdministrador}, para que solo {@link
 * #corridaGlobalProcesaElGrupoDeLaPrueba} recorra de verdad la BD
 * compartida.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioGeneracionTareasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final OffsetDateTime INICIO = ZonedDateTime.of(HOY, LocalTime.MIDNIGHT, ZonaNegocio.ZONA)
            .toOffsetDateTime();
    private static final OffsetDateTime FIN = ZonedDateTime.of(HOY.plusDays(7), LocalTime.MIDNIGHT,
            ZonaNegocio.ZONA).toOffsetDateTime();
    private static final String MOTIVO_RESPONSABLE = "Responsable predeterminado del plan";

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(0, 5).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioGeneracionTareas servicio;

    @MockitoSpyBean
    private RepositorioTareaCuidado repositorioTareas;

    @MockitoSpyBean
    private RepositorioConsultaGeneracion consultaGeneracion;

    @MockitoSpyBean
    private RepositorioOcurrenciaPlanificada repositorioOcurrencias;

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGeneracion fixtura;
    private FixturaTareas fixturaTareas;

    @BeforeEach
    void prepararFixturas() {
        fixtura = new FixturaGeneracion(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Generación normal e idempotencia
    // ------------------------------------------------------------------

    @Test
    void generacionDiariaNormal() {
        Escenario e = escenarioVigente();
        long idResponsable = fixtura.crearResponsable(e.idGrupo(), "ACTIVO");
        assertThat(idResponsable).isGreaterThan(127L);
        Detalle datos = new Detalle(HOY);
        datos.horas = List.of(LocalTime.of(20, 0), LocalTime.of(8, 0));
        datos.dosis = new BigDecimal("1.500");
        datos.idResponsable = idResponsable;
        long idDetalle = fixtura.crearDetalle(e, datos);

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosTareas.TIPO_EJECUCION_GENERACION);
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        assertThat(respuesta.cantidadCreada()).isEqualTo(14);
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(respuesta.resumenErrores()).isNull();
        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(INICIO);
        assertThat(respuesta.finVentana()).isAtSameInstantAs(FIN);
        assertThat(respuesta.fechaInicio()).isNotNull();
        assertThat(respuesta.fechaFin()).isNotNull();

        List<OffsetDateTime> esperados = new ArrayList<>();
        for (int dia = 0; dia < 7; dia++) {
            esperados.add(OffsetDateTime.parse("2026-11-02T08:00-03:00").plusDays(dia));
            esperados.add(OffsetDateTime.parse("2026-11-02T20:00-03:00").plusDays(dia));
        }
        assertMismosInstantes(fixtura.horariosTareas(idDetalle), esperados);

        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        List<Map<String, Object>> ocurrencias = fixtura.ocurrencias(idDetalle);
        assertThat(tareas).hasSize(14);
        assertThat(ocurrencias).hasSize(14);
        for (int i = 0; i < tareas.size(); i++) {
            Map<String, Object> tarea = tareas.get(i);
            Map<String, Object> ocurrencia = ocurrencias.get(i);
            long idTarea = numero(tarea, "ID_TAREA");
            assertThat(tarea.get("CODIGO_ESTADO")).isEqualTo("PENDIENTE");
            assertThat(tarea.get("TIPO_TAREA")).isEqualTo("MEDICAMENTOS");
            assertThat(tarea.get("TITULO_TAREA")).isEqualTo("Paracetamol 500 mg");
            assertThat(tarea.get("DESCRIPCION_TAREA")).isEqualTo("Dosis: 1.5 Comprimido. Con comida");
            assertThat(numero(tarea, "ID_GRUPO")).isEqualTo(e.idGrupo());
            assertThat(numero(tarea, "ID_ADULTO_MAYOR")).isEqualTo(e.idAdulto());
            assertThat(tarea.get("CLAVE_OCURRENCIA"))
                    .isEqualTo(PlanificadorOcurrencias.clave(idDetalle, esperados.get(i), 1));

            assertThat(ocurrencia.get("CODIGO_ESTADO")).isEqualTo("TAREA_CREADA");
            assertThat(numero(ocurrencia, "ID_TAREA")).isEqualTo(idTarea);
            assertThat(ocurrencia.get("CLAVE_OCURRENCIA")).isEqualTo(tarea.get("CLAVE_OCURRENCIA"));

            List<Map<String, Object>> asignaciones = fixtura.asignaciones(idTarea);
            assertThat(asignaciones).hasSize(1);
            assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
            assertThat(numero(asignaciones.get(0), "ID_USUARIO_ASIGNADO")).isEqualTo(idResponsable);
            assertThat(numero(asignaciones.get(0), "ID_USUARIO_ASIGNADOR")).isEqualTo(e.idCoordinador());
            assertThat(asignaciones.get(0).get("MOTIVO")).isEqualTo(MOTIVO_RESPONSABLE);

            // Regla 9: el generador no escribe historial al crear.
            assertThat(fixtura.contarHistorial(idTarea)).isZero();
        }

        Map<String, Object> ejecucion = fixtura.ejecucion(respuesta.idEjecucion());
        assertThat(ejecucion.get("CODIGO_ESTADO")).isEqualTo("EXITOSA");
        assertThat(numero(ejecucion, "CANTIDAD_CREADA")).isEqualTo(14L);
        assertThat(ejecucion.get("TIPO_EJECUCION")).isEqualTo("GENERACION_TAREAS");
    }

    @Test
    void asignadorEsQuienActualizoElPlanSiLoHay() {
        Escenario e = escenarioVigente();
        long idResponsable = fixtura.crearResponsable(e.idGrupo(), "ACTIVO");
        long idEditor = fixtura.crearResponsable(e.idGrupo(), "ACTIVO");
        jdbcTemplate.update("UPDATE PLAN_MEDICAMENTOS SET ID_USUARIO_ACTUALIZACION = ? WHERE ID_PLAN = ?",
                idEditor, e.idPlan());
        Detalle datos = soloLunes();
        datos.idResponsable = idResponsable;
        datos.concentracion = null;
        datos.instrucciones = "  ";
        long idDetalle = fixtura.crearDetalle(e, datos);

        servicio.ejecutar(INICIO, FIN, e.idGrupo());

        Map<String, Object> tarea = fixtura.tareas(idDetalle).get(0);
        assertThat(tarea.get("TITULO_TAREA")).isEqualTo("Paracetamol");
        assertThat(tarea.get("DESCRIPCION_TAREA")).isEqualTo("Dosis: 1 Comprimido");
        Map<String, Object> asignacion = fixtura.asignaciones(numero(tarea, "ID_TAREA")).get(0);
        assertThat(numero(asignacion, "ID_USUARIO_ASIGNADOR")).isEqualTo(idEditor);
    }

    @Test
    void reejecutarNoDuplica() {
        Escenario e = escenarioVigente();
        Detalle datos = new Detalle(HOY);
        datos.horas = List.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        long idDetalle = fixtura.crearDetalle(e, datos);

        RespuestaEjecucion primera = servicio.ejecutar(INICIO, FIN, e.idGrupo());
        RespuestaEjecucion segunda = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(primera.cantidadCreada()).isEqualTo(14);
        assertThat(segunda.cantidadCreada()).isZero();
        assertThat(segunda.cantidadErrores()).isZero();
        assertThat(segunda.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        assertThat(fixtura.tareas(idDetalle)).hasSize(14);
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(14);
    }

    @Test
    void reintentoTrasFalloSinDuplicar() {
        Escenario e = escenarioVigente();
        Detalle datos = new Detalle(HOY);
        datos.horas = List.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        long idDetalle = fixtura.crearDetalle(e, datos);
        OffsetDateTime horarioQueFalla = OffsetDateTime.parse("2026-11-03T08:00-03:00");
        String claveQueFalla = PlanificadorOcurrencias.clave(idDetalle, horarioQueFalla, 1);
        doThrow(new IllegalStateException("Fallo inyectado al guardar la tarea"))
                .when(repositorioTareas).save(argThat((TareaCuidado t) -> t != null
                        && claveQueFalla.equals(t.getClaveOcurrencia())));

        RespuestaEjecucion primera = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(primera.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_PARCIAL));
        assertThat(primera.cantidadCreada()).isEqualTo(13);
        assertThat(primera.cantidadErrores()).isEqualTo(1);
        assertThat(primera.resumenErrores()).contains(claveQueFalla).contains("Fallo inyectado");
        assertThat((String) fixtura.ejecucion(primera.idEjecucion()).get("RESUMEN_ERRORES")).contains(claveQueFalla);
        Map<String, Object> ocurrenciaFallida = fixtura.ocurrencias(idDetalle).stream()
                .filter(o -> claveQueFalla.equals(o.get("CLAVE_OCURRENCIA"))).findFirst().orElseThrow();
        assertThat(ocurrenciaFallida.get("CODIGO_ESTADO")).isEqualTo("ERROR");
        assertThat(ocurrenciaFallida.get("ID_TAREA")).isNull();
        assertThat(fixtura.tareas(idDetalle)).hasSize(13);

        reset(repositorioTareas);
        RespuestaEjecucion segunda = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(segunda.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        assertThat(segunda.cantidadCreada()).isEqualTo(1);
        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        assertThat(tareas).hasSize(14);
        assertThat(tareas.stream().map(t -> t.get("CLAVE_OCURRENCIA")).distinct()).hasSize(14);
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(14)
                .allSatisfy(o -> assertThat(o.get("CODIGO_ESTADO")).isEqualTo("TAREA_CREADA"));
    }

    @Test
    void ocurrenciaEnErrorConTareaExistenteSeVinculaSinCrearOtra() {
        Escenario e = escenarioVigente();
        long idDetalle = fixtura.crearDetalle(e, soloLunes());
        OffsetDateTime horario = OffsetDateTime.parse("2026-11-02T08:00-03:00");
        String clave = PlanificadorOcurrencias.clave(idDetalle, horario, 1);
        long idTarea = fixturaTareas.crearTareaMedicamento(e.idGrupo(), e.idAdulto(), idDetalle, horario,
                "PENDIENTE", clave);
        long idOcurrencia = fixturaTareas.crearOcurrencia(idDetalle, e.idAdulto(), e.idGrupo(), horario, clave,
                "ERROR", null);

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.cantidadErrores()).isZero();
        List<Map<String, Object>> ocurrencias = fixtura.ocurrencias(idDetalle);
        assertThat(ocurrencias).hasSize(1);
        assertThat(numero(ocurrencias.get(0), "ID_OCURRENCIA")).isEqualTo(idOcurrencia);
        assertThat(ocurrencias.get(0).get("CODIGO_ESTADO")).isEqualTo("TAREA_CREADA");
        assertThat(numero(ocurrencias.get(0), "ID_TAREA")).isEqualTo(idTarea);
        assertThat(fixtura.tareas(idDetalle)).hasSize(1);
    }

    /**
     * Regla 4: un turno cuya única tarea está CANCELADA queda libre y se
     * regenera con la revisión 2; pero si el turno ya pasó (00:00 del lunes,
     * con el reloj en 00:05), no se resucita: la fase A nunca reserva turnos
     * pasados (ruling final de F4).
     */
    @Test
    void turnoConTareaCanceladaSeRegeneraConRevisionDosSalvoSiYaPaso() {
        Escenario e = escenarioVigente();
        Detalle datos = soloLunes();
        datos.horas = List.of(LocalTime.MIDNIGHT, LocalTime.of(8, 0));
        long idDetalle = fixtura.crearDetalle(e, datos);
        OffsetDateTime pasado = OffsetDateTime.parse("2026-11-02T00:00-03:00");
        OffsetDateTime futuro = OffsetDateTime.parse("2026-11-02T08:00-03:00");
        for (OffsetDateTime horario : List.of(pasado, futuro)) {
            String clave = PlanificadorOcurrencias.clave(idDetalle, horario, 1);
            long idTarea = fixturaTareas.crearTareaMedicamento(e.idGrupo(), e.idAdulto(), idDetalle, horario,
                    "CANCELADA", clave);
            fixturaTareas.crearOcurrencia(idDetalle, e.idAdulto(), e.idGrupo(), horario, clave, "TAREA_CREADA",
                    idTarea);
        }

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        assertThat(tareas).extracting(t -> t.get("CLAVE_OCURRENCIA")).containsExactly(
                PlanificadorOcurrencias.clave(idDetalle, pasado, 1),
                PlanificadorOcurrencias.clave(idDetalle, futuro, 1),
                PlanificadorOcurrencias.clave(idDetalle, futuro, 2));
        assertThat(tareas).extracting(t -> t.get("CODIGO_ESTADO"))
                .containsExactly("CANCELADA", "CANCELADA", "PENDIENTE");
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(3);
    }

    // ------------------------------------------------------------------
    // Fase B: la ocurrencia ya reservada deja de corresponder (CANCELADA)
    // ------------------------------------------------------------------

    /**
     * La fase A ya reservó la ocurrencia (PLANIFICADA) y el plan se desactiva
     * antes de la fase B: el plan deja de ser candidato (fase A no lo toca)
     * pero la fase B procesa la ocurrencia de la ventana, revalida con el
     * plan bloqueado y la cancela sin crear tarea.
     */
    @Test
    void ocurrenciaQuedaCanceladaSiElPlanSeDesactivoEntreFaseAYFaseB() {
        Escenario e = escenarioVigente();
        long idDetalle = fixtura.crearDetalle(e, soloLunes());
        OffsetDateTime horario = OffsetDateTime.parse("2026-11-02T08:00-03:00");
        long idOcurrencia = fixturaTareas.crearOcurrencia(idDetalle, e.idAdulto(), e.idGrupo(), horario,
                PlanificadorOcurrencias.clave(idDetalle, horario, 1), "PLANIFICADA", null);
        fixtura.cambiarEstado("PLAN_MEDICAMENTOS", "ID_PLAN", e.idPlan(), "INACTIVO");

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.cantidadErrores()).isZero();
        List<Map<String, Object>> ocurrencias = fixtura.ocurrencias(idDetalle);
        assertThat(ocurrencias).hasSize(1);
        assertThat(numero(ocurrencias.get(0), "ID_OCURRENCIA")).isEqualTo(idOcurrencia);
        assertThat(ocurrencias.get(0).get("CODIGO_ESTADO")).isEqualTo("CANCELADA");
        assertThat(ocurrencias.get(0).get("ID_TAREA")).isNull();
        assertThat(fixtura.tareas(idDetalle)).isEmpty();
    }

    /**
     * Mismo caso con el plan ACTIVO: una ocurrencia ERROR de un detalle que
     * se desactivó y una PLANIFICADA cuyo horario (09:00) ya no es esperado
     * por la programación vigente (08:00) se cancelan; el otro detalle
     * ACTIVO del mismo plan genera su tarea normalmente.
     */
    @Test
    void ocurrenciaQuedaCanceladaSiElDetalleSeDesactivoOElTurnoYaNoEsEsperado() {
        Escenario e = escenarioVigente();
        long idDesactivado = fixtura.crearDetalle(e, soloLunes());
        long idCambiado = fixtura.crearDetalle(e, soloLunes());
        OffsetDateTime ocho = OffsetDateTime.parse("2026-11-02T08:00-03:00");
        OffsetDateTime nueve = OffsetDateTime.parse("2026-11-02T09:00-03:00");
        fixturaTareas.crearOcurrencia(idDesactivado, e.idAdulto(), e.idGrupo(), ocho,
                PlanificadorOcurrencias.clave(idDesactivado, ocho, 1), "ERROR", null);
        fixtura.cambiarEstado("DETALLE_PLAN", "ID_DETALLE_PLAN", idDesactivado, "INACTIVO");
        fixturaTareas.crearOcurrencia(idCambiado, e.idAdulto(), e.idGrupo(), nueve,
                PlanificadorOcurrencias.clave(idCambiado, nueve, 1), "PLANIFICADA", null);

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(fixtura.ocurrencias(idDesactivado)).singleElement()
                .satisfies(o -> assertThat(o.get("CODIGO_ESTADO")).isEqualTo("CANCELADA"));
        assertThat(fixtura.tareas(idDesactivado)).isEmpty();
        assertThat(fixtura.ocurrencias(idCambiado)).extracting(o -> o.get("CODIGO_ESTADO"))
                .containsExactly("TAREA_CREADA", "CANCELADA");
        assertMismosInstantes(fixtura.horariosTareas(idCambiado), List.of(ocho));
    }

    // ------------------------------------------------------------------
    // Qué se genera y qué no
    // ------------------------------------------------------------------

    @Test
    void omiteAdultoPlanDetalleYGrupoInactivos() {
        Escenario conAdultoInactivo = escenarioVigente();
        long detalleAdulto = fixtura.crearDetalle(conAdultoInactivo, soloLunes());
        fixtura.cambiarEstado("ADULTO_MAYOR", "ID_ADULTO_MAYOR", conAdultoInactivo.idAdulto(), "INACTIVO");

        Escenario conPlanInactivo = escenarioVigente();
        long detallePlan = fixtura.crearDetalle(conPlanInactivo, soloLunes());
        fixtura.cambiarEstado("PLAN_MEDICAMENTOS", "ID_PLAN", conPlanInactivo.idPlan(), "INACTIVO");

        Escenario conDetalleInactivo = escenarioVigente();
        long detalleInactivo = fixtura.crearDetalle(conDetalleInactivo, soloLunes());
        fixtura.cambiarEstado("DETALLE_PLAN", "ID_DETALLE_PLAN", detalleInactivo, "INACTIVO");

        Escenario conGrupoInactivo = escenarioVigente();
        long detalleGrupo = fixtura.crearDetalle(conGrupoInactivo, soloLunes());
        fixtura.cambiarEstado("GRUPO_FAMILIAR", "ID_GRUPO", conGrupoInactivo.idGrupo(), "INACTIVO");

        for (Escenario e : List.of(conAdultoInactivo, conPlanInactivo, conDetalleInactivo, conGrupoInactivo)) {
            RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());
            assertThat(respuesta.cantidadCreada()).isZero();
            assertThat(respuesta.cantidadErrores()).isZero();
        }
        for (long idDetalle : List.of(detalleAdulto, detallePlan, detalleInactivo, detalleGrupo)) {
            assertThat(fixtura.tareas(idDetalle)).isEmpty();
            assertThat(fixtura.ocurrencias(idDetalle)).isEmpty();
        }
    }

    @Test
    void respetaDiasYVigencia() {
        Escenario e = escenarioVigente();
        Detalle datos = new Detalle(HOY);
        datos.dias = List.of("LUNES", "MIERCOLES");
        datos.fechaFin = LocalDate.of(2026, 11, 4);
        long idDetalle = fixtura.crearDetalle(e, datos);

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isEqualTo(2);
        assertMismosInstantes(fixtura.horariosTareas(idDetalle), List.of(
                OffsetDateTime.parse("2026-11-02T08:00-03:00"), OffsetDateTime.parse("2026-11-04T08:00-03:00")));
    }

    @Test
    void vigenciaDelPlanAcotaLaDelDetalle() {
        Escenario e = fixtura.escenario(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));
        long idDetalle = fixtura.crearDetalle(e, new Detalle(HOY));

        servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertMismosInstantes(fixtura.horariosTareas(idDetalle), List.of(
                OffsetDateTime.parse("2026-11-02T08:00-03:00"), OffsetDateTime.parse("2026-11-03T08:00-03:00")));
    }

    @Test
    void detalleSinHorasRegistraErrorYNoBloqueaLosDemas() {
        Escenario e = escenarioVigente();
        Detalle sinHoras = new Detalle(HOY);
        sinHoras.horas = List.of();
        long idSinHoras = fixtura.crearDetalle(e, sinHoras);
        long idNormal = fixtura.crearDetalle(e, soloLunes());

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_PARCIAL));
        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        assertThat(respuesta.cantidadErrores()).isEqualTo(1);
        assertThat(respuesta.resumenErrores()).contains("Detalle " + idSinHoras + ": sin días u horas válidos");
        assertThat(fixtura.tareas(idNormal)).hasSize(1);
        assertThat(fixtura.tareas(idSinHoras)).isEmpty();
    }

    @Test
    void responsableInactivoDejaLaTareaSinAsignar() {
        Escenario e = escenarioVigente();
        Detalle datos = soloLunes();
        datos.idResponsable = fixtura.crearResponsable(e.idGrupo(), "INACTIVO");
        long idDetalle = fixtura.crearDetalle(e, datos);

        RespuestaEjecucion respuesta = servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isEqualTo(1);
        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        assertThat(tareas).hasSize(1);
        assertThat(fixtura.asignaciones(numero(tareas.get(0), "ID_TAREA"))).isEmpty();
    }

    @Test
    void responsableDeOtroGrupoDejaLaTareaSinAsignar() {
        Escenario e = escenarioVigente();
        Escenario otro = escenarioVigente();
        Detalle datos = soloLunes();
        datos.idResponsable = fixtura.crearResponsable(otro.idGrupo(), "ACTIVO");
        long idDetalle = fixtura.crearDetalle(e, datos);

        servicio.ejecutar(INICIO, FIN, e.idGrupo());

        assertThat(fixtura.asignaciones(numero(fixtura.tareas(idDetalle).get(0), "ID_TAREA"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // Concurrencia
    // ------------------------------------------------------------------

    @Test
    void corridasConcurrentesNoDuplican() throws Exception {
        Escenario e = escenarioVigente();
        Detalle datos = new Detalle(HOY);
        datos.horas = List.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        datos.idResponsable = fixtura.crearResponsable(e.idGrupo(), "ACTIVO");
        long idDetalle = fixtura.crearDetalle(e, datos);

        CyclicBarrier barrera = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Future<RespuestaEjecucion>> corridas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                corridas.add(hilos.submit(() -> {
                    barrera.await(30, TimeUnit.SECONDS);
                    return servicio.ejecutar(INICIO, FIN, e.idGrupo());
                }));
            }
            int creadas = 0;
            for (Future<RespuestaEjecucion> corrida : corridas) {
                RespuestaEjecucion respuesta = corrida.get(120, TimeUnit.SECONDS);
                assertThat(respuesta.cantidadErrores()).isZero();
                creadas += respuesta.cantidadCreada();
            }
            assertThat(creadas).isEqualTo(14);
        } finally {
            hilos.shutdownNow();
        }

        List<Map<String, Object>> tareas = fixtura.tareas(idDetalle);
        assertThat(tareas).hasSize(14);
        assertThat(tareas.stream().map(t -> t.get("CLAVE_OCURRENCIA")).distinct()).hasSize(14);
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(14);
        for (Map<String, Object> tarea : tareas) {
            assertThat(fixtura.asignaciones(numero(tarea, "ID_TAREA"))).hasSize(1);
        }
    }

    // ------------------------------------------------------------------
    // Corrida manual y programada
    // ------------------------------------------------------------------

    @Test
    void generarManualExigeAdministrador() {
        autenticar(fixtura.crearUsuario(false));
        assertThatThrownBy(() -> servicio.generarManual()).isInstanceOf(ExcepcionAccesoDenegado.class);

        String uidAdministrador = fixtura.crearUsuario(true);
        long idAdministrador = jdbcTemplate.queryForObject(
                "SELECT ID_USUARIO FROM USUARIO WHERE UID_FIREBASE = ?", Long.class, uidAdministrador);
        autenticar(uidAdministrador);
        // La corrida manual es global (sin grupo): se deja vacía para no
        // recorrer ni escribir la BD compartida. Solo se prueba aquí el
        // permiso, la ventana, la auditoría y el listado.
        doReturn(List.of()).when(consultaGeneracion).planesCandidatos(any(), any(), isNull());
        doReturn(List.of()).when(repositorioOcurrencias).idsPorProcesar(any(), any(), any(), isNull());

        RespuestaEjecucion respuesta = servicio.generarManual();

        verify(consultaGeneracion).planesCandidatos(any(), any(), isNull());
        verify(repositorioOcurrencias).idsPorProcesar(any(), any(), any(), isNull());
        assertThat(respuesta.cantidadCreada()).isZero();
        assertThat(respuesta.idEstado()).isEqualTo(idEstadoEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA));

        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(INICIO);
        assertThat(respuesta.finVentana()).isAtSameInstantAs(FIN);
        assertThat(respuesta.fechaFin()).isNotNull();
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, ID_GRUPO FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'EJECUCION_PROCESO' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = ?",
                String.valueOf(respuesta.idEjecucion()), AccionesTareas.EJECUTAR_GENERACION_TAREAS);
        assertThat(eventos).hasSize(1);
        assertThat(numero(eventos.get(0), "ID_USUARIO_ACTOR")).isEqualTo(idAdministrador);
        assertThat(eventos.get(0).get("ID_GRUPO")).isNull();

        assertThat(servicio.listarEjecuciones(CodigosTareas.TIPO_EJECUCION_GENERACION))
                .extracting(RespuestaEjecucion::idEjecucion).contains(respuesta.idEjecucion());
    }

    /**
     * Una corrida manual que falla fuera de los pasos por plan u ocurrencia
     * (aquí, la consulta de candidatos) relanza el error, pero igual deja
     * auditado {@code EJECUTAR_GENERACION_TAREAS} con resultado FALLO, en
     * transacción propia, con el administrador como actor y sin el mensaje.
     */
    @Test
    void corridaManualFallidaQuedaAuditadaComoFallo() {
        String uidAdministrador = fixtura.crearUsuario(true);
        long idAdministrador = jdbcTemplate.queryForObject(
                "SELECT ID_USUARIO FROM USUARIO WHERE UID_FIREBASE = ?", Long.class, uidAdministrador);
        autenticar(uidAdministrador);
        doThrow(new IllegalStateException("Fallo inyectado en la consulta de candidatos"))
                .when(consultaGeneracion).planesCandidatos(any(), any(), isNull());

        // El spy del repositorio traduce la excepción (DataAccessException); importa que se relance.
        assertThatThrownBy(() -> servicio.generarManual()).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Fallo inyectado en la consulta de candidatos");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ea.ID_ENTIDAD, ea.ID_GRUPO, DBMS_LOB.SUBSTR(ea.DETALLES_JSON, 4000, 1) AS DETALLES, "
                        + "ce.CODIGO_ESTADO AS RESULTADO "
                        + "FROM EVENTO_AUDITORIA ea JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = ea.ID_RESULTADO "
                        + "WHERE ea.TIPO_ENTIDAD = 'EJECUCION_PROCESO' AND ea.CODIGO_ACCION = ? "
                        + "AND ea.ID_USUARIO_ACTOR = ?",
                AccionesTareas.EJECUTAR_GENERACION_TAREAS, idAdministrador);
        assertThat(eventos).hasSize(1);
        Map<String, Object> evento = eventos.get(0);
        assertThat(evento.get("RESULTADO")).isEqualTo("FALLO");
        assertThat(evento.get("ID_GRUPO")).isNull();
        assertThat((String) evento.get("DETALLES")).doesNotContain("Fallo inyectado");
        long idEjecucion = Long.parseLong((String) evento.get("ID_ENTIDAD"));
        assertThat(fixtura.ejecucion(idEjecucion).get("CODIGO_ESTADO")).isEqualTo("FALLIDA");
    }

    @Test
    void listarEjecucionesExigeAdministrador() {
        autenticar(fixtura.crearUsuario(false));

        assertThatThrownBy(() -> servicio.listarEjecuciones(CodigosTareas.TIPO_EJECUCION_GENERACION))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    /**
     * {@code src/test/resources/config/application.properties} apaga el
     * disparo programado en todos los contextos de prueba, sin tapar el
     * {@code application.properties} de main (sus propiedades siguen
     * cargadas).
     */
    @Test
    void programadorProcesosNoSeRegistraEnLasPruebas() {
        assertThat(contexto.getBeanNamesForType(ProgramadorProcesos.class)).isEmpty();
        assertThat(contexto.getEnvironment().getProperty("mejorvejez.procesos.habilitados")).isEqualTo("false");
        assertThat(contexto.getEnvironment().getProperty("mejorvejez.tareas.dias-ventana")).isEqualTo("7");
    }

    @Test
    void corridaGlobalProcesaElGrupoDeLaPrueba() {
        Escenario e = escenarioVigente();
        long idDetalle = fixtura.crearDetalle(e, soloLunes());

        RespuestaEjecucion respuesta = servicio.generarProgramado();

        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(INICIO);
        assertThat(respuesta.finVentana()).isAtSameInstantAs(FIN);
        assertMismosInstantes(fixtura.horariosTareas(idDetalle),
                List.of(OffsetDateTime.parse("2026-11-02T08:00-03:00")));
        assertThat(fixtura.ocurrencias(idDetalle)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenarioVigente() {
        return fixtura.escenario(LocalDate.of(2026, 11, 1), null);
    }

    private static Detalle soloLunes() {
        Detalle datos = new Detalle(HOY);
        datos.dias = List.of("LUNES");
        return datos;
    }

    private static void assertMismosInstantes(List<OffsetDateTime> reales, List<OffsetDateTime> esperados) {
        assertThat(reales.stream().map(OffsetDateTime::toInstant).toList())
                .containsExactlyElementsOf(esperados.stream().map(OffsetDateTime::toInstant).toList());
    }

    private long idEstadoEjecucion(String codigo) {
        return fixtura.idEstado(CodigosTareas.TABLA_EJECUCION_PROCESO, codigo);
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
