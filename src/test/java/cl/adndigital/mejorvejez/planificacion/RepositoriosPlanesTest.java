package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;

/**
 * Prueba las entidades y repositorios del módulo {@code planificacion}
 * contra Oracle real (Testcontainers). Los datos los crea {@link
 * FixturaPlanes} / {@link FixturaGrupos} / {@link FixturaModelo} con IDs
 * propios; quedan confirmados en la base compartida, así que cada prueba
 * filtra siempre por sus propios IDs y nunca cuenta tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class RepositoriosPlanesTest {

    @Autowired
    private RepositorioPlanMedicamentos repositorioPlanMedicamentos;

    @Autowired
    private RepositorioDetallePlan repositorioDetallePlan;

    @Autowired
    private RepositorioHistorialPlan repositorioHistorialPlan;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaPlanes fixturaPlanes;
    private TransactionTemplate plantillaTransaccion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        plantillaTransaccion = new TransactionTemplate(gestorTransacciones);
    }

    @Test
    void bloquearPorIdDevuelveElPlan() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");

        Optional<PlanMedicamentos> encontrado =
                plantillaTransaccion.execute(estado -> repositorioPlanMedicamentos.bloquearPorId(idPlan));

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getIdPlan()).isEqualTo(idPlan);
    }

    @Test
    void losPlanesDelGrupoYDelAdultoSeListanPorFechaDeCreacionDescendente() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlanUno = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idPlanDos = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");

        List<PlanMedicamentos> delGrupo =
                repositorioPlanMedicamentos.findByIdGrupoOrderByFechaCreacionDescIdPlanDesc(idGrupo);
        List<PlanMedicamentos> delGrupoYAdulto = repositorioPlanMedicamentos
                .findByIdGrupoAndIdAdultoMayorOrderByFechaCreacionDescIdPlanDesc(idGrupo, idAdulto);

        assertThat(delGrupo).extracting(PlanMedicamentos::getIdPlan).containsExactly(idPlanDos, idPlanUno);
        assertThat(delGrupoYAdulto).extracting(PlanMedicamentos::getIdPlan).containsExactly(idPlanDos, idPlanUno);
    }

    @Test
    void elDesempatePorIdPlanOrdenaPlanesConLaMismaFechaDeCreacion() {
        // Dos planes con exactamente la misma FECHA_CREACION (forzada por la
        // prueba: SYSTIMESTAMP normalmente no se repite, pero puede coincidir
        // en la práctica): sin el desempate por ID_PLAN su orden entre ellos
        // quedaría indefinido (Tarea 4, extra #5).
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlanUno = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idPlanDos = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");

        jdbcTemplate.update("UPDATE PLAN_MEDICAMENTOS SET FECHA_CREACION = "
                + "(SELECT FECHA_CREACION FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?) WHERE ID_PLAN = ?",
                idPlanUno, idPlanDos);

        List<PlanMedicamentos> delGrupo =
                repositorioPlanMedicamentos.findByIdGrupoOrderByFechaCreacionDescIdPlanDesc(idGrupo);

        assertThat(delGrupo).extracting(PlanMedicamentos::getIdPlan).containsExactly(idPlanDos, idPlanUno);
    }

    @Test
    void guardaYLeeDiasYHoras() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        long idLunes = fixturaModelo.idOpcion("DIA_SEMANA", "LUNES");
        long idMiercoles = fixturaModelo.idOpcion("DIA_SEMANA", "MIERCOLES");

        plantillaTransaccion.executeWithoutResult(estado -> {
            DetallePlan detalle = repositorioDetallePlan.findById(idDetalle).orElseThrow();
            detalle.reemplazarDias(Set.of(idLunes, idMiercoles));
            detalle.reemplazarHorario(List.of(LocalTime.of(8, 0), LocalTime.of(20, 30)));
        });

        // Se relee en una transacción NUEVA (no la que escribió): confirma que
        // quedó de verdad en la base, no solo en la sesión que escribió.
        // Los valores se extraen dentro de esa transacción porque las
        // colecciones son LAZY (fuera de la transacción ya no hay sesión).
        Set<Long> diasReleidos = plantillaTransaccion.execute(
                estado -> repositorioDetallePlan.findById(idDetalle).orElseThrow().diasSemana());
        List<LocalTime> horasReleidas = plantillaTransaccion.execute(
                estado -> repositorioDetallePlan.findById(idDetalle).orElseThrow().horas());

        assertThat(diasReleidos).containsExactlyInAnyOrder(idLunes, idMiercoles);
        assertThat(horasReleidas).containsExactly(LocalTime.of(8, 0), LocalTime.of(20, 30));
    }

    @Test
    void reemplazarHorarioBorraLasHorasAnteriores() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Ibuprofeno");
        // crearDetalle ya deja una hora sembrada (08:00).
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        plantillaTransaccion.executeWithoutResult(estado -> {
            DetallePlan detalle = repositorioDetallePlan.findById(idDetalle).orElseThrow();
            detalle.reemplazarHorario(List.of(LocalTime.of(14, 0)));
        });

        List<LocalTime> horasReleidas = plantillaTransaccion.execute(
                estado -> repositorioDetallePlan.findById(idDetalle).orElseThrow().horas());

        assertThat(horasReleidas).containsExactly(LocalTime.of(14, 0));
    }

    @Test
    void reemplazarHorarioGuardaLaHoraDeRelojSinConversionDeZona() {
        // Guardia de zona horaria (Tarea 4, extra #6a): si alguien fija
        // hibernate.jdbc.time_zone, Hibernate desplazaría esta hora al
        // escribirla; se lee la columna cruda por JDBC (sin pasar por el
        // mapeo de Hibernate) para confirmar que quedó literal.
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Clonazepam");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        plantillaTransaccion.executeWithoutResult(estado -> {
            DetallePlan detalle = repositorioDetallePlan.findById(idDetalle).orElseThrow();
            detalle.reemplazarHorario(List.of(LocalTime.of(23, 30)));
        });

        String horaCruda = jdbcTemplate.queryForObject(
                "SELECT TO_CHAR(HORA_ADMINISTRACION, 'YYYY-MM-DD HH24:MI:SS') FROM DETALLE_PLAN_HORARIO "
                        + "WHERE ID_DETALLE_PLAN = ?",
                String.class, idDetalle);

        assertThat(horaCruda).isEqualTo("1970-01-01 23:30:00");
    }

    @Test
    void reemplazarDiasYHorarioAgregaSinPerderLosQueSeRepiten() {
        // Reemplazo que solapa con el anterior (Tarea 4, extra #6b): de
        // [08:00] a [08:00, 14:00] y de LUNES a LUNES+MIERCOLES deben quedar
        // ambos valores, sin que el borrado-e-inserción de reemplazarHorario/
        // reemplazarDias pierda el que se repite.
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Enalapril");
        // crearDetalle ya deja LUNES + 08:00 sembrados.
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        long idLunes = fixturaModelo.idOpcion("DIA_SEMANA", "LUNES");
        long idMiercoles = fixturaModelo.idOpcion("DIA_SEMANA", "MIERCOLES");

        plantillaTransaccion.executeWithoutResult(estado -> {
            DetallePlan detalle = repositorioDetallePlan.findById(idDetalle).orElseThrow();
            detalle.reemplazarHorario(List.of(LocalTime.of(8, 0), LocalTime.of(14, 0)));
            detalle.reemplazarDias(Set.of(idLunes, idMiercoles));
        });

        Set<Long> diasReleidos = plantillaTransaccion.execute(
                estado -> repositorioDetallePlan.findById(idDetalle).orElseThrow().diasSemana());
        List<LocalTime> horasReleidas = plantillaTransaccion.execute(
                estado -> repositorioDetallePlan.findById(idDetalle).orElseThrow().horas());

        assertThat(diasReleidos).containsExactlyInAnyOrder(idLunes, idMiercoles);
        assertThat(horasReleidas).containsExactly(LocalTime.of(8, 0), LocalTime.of(14, 0));
    }

    @Test
    void checkDeBdRechazaHoraConSegundos() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Amoxicilina");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) "
                        + "VALUES (?, TIMESTAMP '1970-01-01 08:00:05')",
                idDetalle));
    }

    @Test
    void detalleSinNadaNoTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartan");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        assertThat(repositorioHistorialPlan.detalleTieneReferencias(idDetalle)).isFalse();
    }

    @Test
    void detalleConTareaTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Metformina");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        fixturaPlanes.crearTareaDeMedicamento(idGrupo, idAdulto, idDetalle, OffsetDateTime.now());

        assertThat(repositorioHistorialPlan.detalleTieneReferencias(idDetalle)).isTrue();
    }

    @Test
    void planConDetalleDesactivadoAlgunaVezTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Omeprazol");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        // El plan en sí nunca fue desactivado; solo su detalle lo fue alguna vez.
        insertarEventoAuditoria("DETALLE_PLAN", idDetalle, idGrupo, idCreador, "DESACTIVAR_MEDICAMENTO_PLAN");

        assertThat(repositorioHistorialPlan.planTieneReferencias(idPlan)).isTrue();
    }

    private void insertarEventoAuditoria(String tipoEntidad, long idEntidad, long idGrupo, long idActor,
            String codigoAccion) {
        long idResultadoExito = fixturaModelo.idEstado("EVENTO_AUDITORIA", "RESULTADO", "EXITO");
        jdbcTemplate.update(
                "INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, "
                        + "ID_USUARIO_ACTOR, CODIGO_ACCION, ID_RESULTADO) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "planificacion", tipoEntidad, String.valueOf(idEntidad), idGrupo, idActor, codigoAccion,
                idResultadoExito);
    }
}
