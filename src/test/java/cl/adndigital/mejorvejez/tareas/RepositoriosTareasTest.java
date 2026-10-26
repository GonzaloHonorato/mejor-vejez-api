package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Prueba las entidades y repositorios del módulo {@code tareas} contra
 * Oracle real (Testcontainers). Los datos los crea {@link FixturaTareas} /
 * {@link FixturaPlanes} / {@link FixturaModelo} / {@link FixturaGrupos} con
 * IDs propios; quedan confirmados en la base compartida, así que cada prueba
 * filtra siempre por sus propios IDs y nunca cuenta tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class RepositoriosTareasTest {

    @Autowired
    private RepositorioTareaCuidado repositorioTareaCuidado;

    @Autowired
    private RepositorioOcurrenciaPlanificada repositorioOcurrenciaPlanificada;

    @Autowired
    private RepositorioAsignacionTarea repositorioAsignacionTarea;

    @Autowired
    private RepositorioHistorialEstadoTarea repositorioHistorialEstadoTarea;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaPlanes fixturaPlanes;
    private FixturaTareas fixturaTareas;
    private TransactionTemplate plantillaTransaccion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        plantillaTransaccion = new TransactionTemplate(gestorTransacciones);
    }

    @Test
    void guardarYLeerTareaDeMedicamentoConservaElInstante() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        long idEstadoPendiente = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        // Instante exacto del ruling de zona horaria (regla 2 de la Fase 4).
        OffsetDateTime horario = OffsetDateTime.parse("2026-11-02T08:00:00-03:00");

        Long idTarea = plantillaTransaccion.execute(estado -> repositorioTareaCuidado
                .save(TareaCuidado.deMedicamento(FixturaTareas.claveUnica(), idGrupo, idAdulto, idDetalle,
                        "Paracetamol 500 mg", "Tomar con agua", horario, idEstadoPendiente, horario))
                .getIdTarea());

        OffsetDateTime horarioReleido = plantillaTransaccion
                .execute(estado -> repositorioTareaCuidado.findById(idTarea).orElseThrow().getFechaHorario());

        assertThat(horarioReleido.isEqual(horario)).isTrue();
    }

    @Test
    void claveDeOcurrenciaRepetidaFalla() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Ibuprofeno");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        long idEstadoPendiente = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");
        String claveRepetida = FixturaTareas.claveUnica();
        OffsetDateTime horario = OffsetDateTime.now();

        repositorioTareaCuidado.saveAndFlush(TareaCuidado.deMedicamento(claveRepetida, idGrupo, idAdulto, idDetalle,
                "Tarea uno", "Descripción uno", horario, idEstadoPendiente, horario));

        assertThrows(DataIntegrityViolationException.class, () -> repositorioTareaCuidado.saveAndFlush(
                TareaCuidado.deMedicamento(claveRepetida, idGrupo, idAdulto, idDetalle, "Tarea dos",
                        "Descripción dos", horario, idEstadoPendiente, horario)));
    }

    @Test
    void dosAsignacionesActivasDeLaMismaTareaFallan() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idUsuarioUno = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idUsuarioDos = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idTarea = fixturaTareas.crearTareaManual(idGrupo, idAdulto, "CITAS_CONTROLES_MEDICOS",
                OffsetDateTime.now());
        long idEstadoActivo = fixturaModelo.idEstado("ASIGNACION_TAREA", "ESTADO", "ACTIVO");

        repositorioAsignacionTarea.saveAndFlush(new AsignacionTarea(idTarea, idGrupo, idUsuarioUno, idCreador, null,
                idEstadoActivo, OffsetDateTime.now()));

        assertThrows(DataIntegrityViolationException.class,
                () -> repositorioAsignacionTarea.saveAndFlush(new AsignacionTarea(idTarea, idGrupo, idUsuarioDos,
                        idCreador, null, idEstadoActivo, OffsetDateTime.now())));
    }

    @Test
    void reemplazarAsignacionConFlushPrevioFunciona() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idUsuarioUno = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idUsuarioDos = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idTarea = fixturaTareas.crearTareaManual(idGrupo, idAdulto, "CITAS_CONTROLES_MEDICOS",
                OffsetDateTime.now());
        long idAsignacionUno = fixturaTareas.crearAsignacion(idTarea, idGrupo, idUsuarioUno, idCreador);
        long idEstadoActivo = fixturaModelo.idEstado("ASIGNACION_TAREA", "ESTADO", "ACTIVO");
        long idEstadoReemplazada = fixturaModelo.idEstado("ASIGNACION_TAREA", "ESTADO", "REEMPLAZADA");
        OffsetDateTime ahora = OffsetDateTime.now();

        // Orden exigido por la restricción global: flush de la anterior
        // (REEMPLAZADA) ANTES de insertar la nueva ACTIVO.
        plantillaTransaccion.executeWithoutResult(estado -> {
            AsignacionTarea anterior = repositorioAsignacionTarea.findById(idAsignacionUno).orElseThrow();
            anterior.terminar(idEstadoReemplazada, ahora);
            repositorioAsignacionTarea.saveAndFlush(anterior);

            repositorioAsignacionTarea.saveAndFlush(new AsignacionTarea(idTarea, idGrupo, idUsuarioDos, idCreador,
                    "Reasignación de prueba", idEstadoActivo, ahora));
        });

        AsignacionTarea anteriorReleida = repositorioAsignacionTarea.findById(idAsignacionUno).orElseThrow();
        Optional<AsignacionTarea> activaActual =
                repositorioAsignacionTarea.findByIdTareaAndIdEstado(idTarea, idEstadoActivo);

        assertThat(anteriorReleida.getIdEstado()).isEqualTo(idEstadoReemplazada);
        assertThat(anteriorReleida.getFechaTermino()).isNotNull();
        assertThat(activaActual).isPresent();
        assertThat(activaActual.get().getIdUsuarioAsignado()).isEqualTo(idUsuarioDos);
    }

    @Test
    void turnoOcupadoSegunLaRegla() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Clonazepam");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        long idEstadoPlanificada = fixturaModelo.idEstado("OCURRENCIA_PLANIFICADA", "ESTADO", "PLANIFICADA");
        long idEstadoError = fixturaModelo.idEstado("OCURRENCIA_PLANIFICADA", "ESTADO", "ERROR");
        long idEstadoTareaCreadaOcurrencia = fixturaModelo.idEstado("OCURRENCIA_PLANIFICADA", "ESTADO",
                "TAREA_CREADA");
        long idEstadoTareaCancelada = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "CANCELADA");
        List<Long> idsEstadoAbiertos = List.of(idEstadoPlanificada, idEstadoError);

        OffsetDateTime horarioPlanificada = OffsetDateTime.now().plusDays(1).withNano(0);
        OffsetDateTime horarioTareaPendiente = horarioPlanificada.plusHours(1);
        OffsetDateTime horarioTareaCancelada = horarioPlanificada.plusHours(2);
        OffsetDateTime horarioCancelada = horarioPlanificada.plusHours(3);

        // Turno con una ocurrencia PLANIFICADA: ocupado.
        fixturaTareas.crearOcurrencia(idDetalle, idAdulto, idGrupo, horarioPlanificada, FixturaTareas.claveUnica(),
                "PLANIFICADA", null);

        // Turno con TAREA_CREADA cuya tarea sigue PENDIENTE: ocupado.
        long idTareaPendiente = fixturaTareas.crearTareaMedicamento(idGrupo, idAdulto, idDetalle,
                horarioTareaPendiente, "PENDIENTE", FixturaTareas.claveUnica());
        fixturaTareas.crearOcurrencia(idDetalle, idAdulto, idGrupo, horarioTareaPendiente,
                FixturaTareas.claveUnica(), "TAREA_CREADA", idTareaPendiente);

        // Turno con TAREA_CREADA cuya tarea está CANCELADA: libre (puede regenerarse con revisión siguiente).
        long idTareaCancelada = fixturaTareas.crearTareaMedicamento(idGrupo, idAdulto, idDetalle,
                horarioTareaCancelada, "CANCELADA", FixturaTareas.claveUnica());
        fixturaTareas.crearOcurrencia(idDetalle, idAdulto, idGrupo, horarioTareaCancelada,
                FixturaTareas.claveUnica(), "TAREA_CREADA", idTareaCancelada);

        // Turno con una ocurrencia CANCELADA (sin tarea creada): libre.
        fixturaTareas.crearOcurrencia(idDetalle, idAdulto, idGrupo, horarioCancelada, FixturaTareas.claveUnica(),
                "CANCELADA", null);

        assertThat(repositorioOcurrenciaPlanificada.turnoOcupado(idDetalle, horarioPlanificada, idsEstadoAbiertos,
                idEstadoTareaCreadaOcurrencia, idEstadoTareaCancelada)).isTrue();
        assertThat(repositorioOcurrenciaPlanificada.turnoOcupado(idDetalle, horarioTareaPendiente, idsEstadoAbiertos,
                idEstadoTareaCreadaOcurrencia, idEstadoTareaCancelada)).isTrue();
        assertThat(repositorioOcurrenciaPlanificada.turnoOcupado(idDetalle, horarioTareaCancelada, idsEstadoAbiertos,
                idEstadoTareaCreadaOcurrencia, idEstadoTareaCancelada)).isFalse();
        assertThat(repositorioOcurrenciaPlanificada.turnoOcupado(idDetalle, horarioCancelada, idsEstadoAbiertos,
                idEstadoTareaCreadaOcurrencia, idEstadoTareaCancelada)).isFalse();
    }

    @Test
    void buscarAgendaFiltraPorRangoAdultoEstadoYAsignado() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdultoUno = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idAdultoDos = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idUsuarioAsignado = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idEstadoPendiente = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");
        long idEstadoEnCurso = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "EN_CURSO");
        long idEstadoAsignacionActiva = fixturaModelo.idEstado("ASIGNACION_TAREA", "ESTADO", "ACTIVO");

        OffsetDateTime desde = OffsetDateTime.parse("2026-11-02T00:00:00-03:00");
        OffsetDateTime hasta = desde.plusDays(7);

        // Antes de la ventana: nunca debe aparecer.
        fixturaTareas.crearTareaManual(idGrupo, idAdultoUno, "CITAS_CONTROLES_MEDICOS", desde.minusMinutes(1));
        // Límite inferior incluido.
        long idTareaEnDesde =
                fixturaTareas.crearTareaManual(idGrupo, idAdultoUno, "CITAS_CONTROLES_MEDICOS", desde);
        // Dentro del rango, y además asignada (para el filtro por responsable).
        long idTareaEnRangoAsignada = fixturaTareas.crearTareaManual(idGrupo, idAdultoUno,
                "CITAS_CONTROLES_MEDICOS", desde.plusDays(3));
        fixturaTareas.crearAsignacion(idTareaEnRangoAsignada, idGrupo, idUsuarioAsignado, idCreador);
        // Límite superior excluido.
        fixturaTareas.crearTareaManual(idGrupo, idAdultoUno, "CITAS_CONTROLES_MEDICOS", hasta);
        // Dentro del rango, pero de otro adulto.
        long idTareaOtroAdulto = fixturaTareas.crearTareaManual(idGrupo, idAdultoDos,
                "CITAS_CONTROLES_MEDICOS", desde.plusDays(2));
        // Dentro del rango, mismo adulto, pero EN_CURSO (no PENDIENTE).
        long idTareaOtroEstado = fixturaTareas.crearTareaManual(idGrupo, idAdultoUno,
                "CITAS_CONTROLES_MEDICOS", desde.plusDays(4));
        plantillaTransaccion.executeWithoutResult(estado -> {
            TareaCuidado tarea = repositorioTareaCuidado.findById(idTareaOtroEstado).orElseThrow();
            tarea.cambiarEstado(idEstadoEnCurso, OffsetDateTime.now());
        });
        // Dentro del rango, mismo adulto, PENDIENTE, pero sin asignar.
        long idTareaSinAsignar = fixturaTareas.crearTareaManual(idGrupo, idAdultoUno, "CITAS_CONTROLES_MEDICOS",
                desde.plusDays(5));

        List<TareaCuidado> sinFiltrosOpcionales =
                repositorioTareaCuidado.buscarAgenda(idGrupo, desde, hasta, null, null, null, idEstadoAsignacionActiva);
        assertThat(sinFiltrosOpcionales).extracting(TareaCuidado::getIdTarea).containsExactlyInAnyOrder(
                idTareaEnDesde, idTareaEnRangoAsignada, idTareaOtroAdulto, idTareaOtroEstado, idTareaSinAsignar);

        List<TareaCuidado> filtradoPorAdulto = repositorioTareaCuidado.buscarAgenda(idGrupo, desde, hasta,
                idAdultoUno, null, null, idEstadoAsignacionActiva);
        assertThat(filtradoPorAdulto).extracting(TareaCuidado::getIdTarea).containsExactlyInAnyOrder(idTareaEnDesde,
                idTareaEnRangoAsignada, idTareaOtroEstado, idTareaSinAsignar);

        List<TareaCuidado> filtradoPorEstado = repositorioTareaCuidado.buscarAgenda(idGrupo, desde, hasta,
                idAdultoUno, idEstadoPendiente, null, idEstadoAsignacionActiva);
        assertThat(filtradoPorEstado).extracting(TareaCuidado::getIdTarea)
                .containsExactlyInAnyOrder(idTareaEnDesde, idTareaEnRangoAsignada, idTareaSinAsignar);

        List<TareaCuidado> filtradoPorAsignado = repositorioTareaCuidado.buscarAgenda(idGrupo, desde, hasta, null,
                null, idUsuarioAsignado, idEstadoAsignacionActiva);
        assertThat(filtradoPorAsignado).extracting(TareaCuidado::getIdTarea)
                .containsExactly(idTareaEnRangoAsignada);
    }

    @Test
    void historialNoSePuedeModificar() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaTareas.crearTareaManual(idGrupo, idAdulto, "CITAS_CONTROLES_MEDICOS",
                OffsetDateTime.now());
        long idEstadoPendiente = fixturaModelo.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        long idHistorial = repositorioHistorialEstadoTarea
                .save(new HistorialEstadoTarea(idTarea, null, idEstadoPendiente, idCreador, "Creación de la tarea",
                        OffsetDateTime.now()))
                .getIdHistorial();

        DataAccessException excepcion = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE HISTORIAL_ESTADO_TAREA SET OBSERVACION = 'otra' WHERE ID_HISTORIAL = ?", idHistorial));

        assertThat(excepcion.getMessage()).contains("ORA-20011");
    }
}
