package cl.adndigital.mejorvejez.planificacion;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Datos de un plan de medicamentos para quien lo consulta o lo muta ({@code
 * GET}/{@code POST .../planes}, {@code PUT}/{@code POST .../desactivar}/
 * {@code .../reactivar .../planes/{idPlan}}).
 *
 * @param nombreAdulto         {@code nombreCompleto} del adulto mayor del
 *                             plan, resuelto por el servicio sin N+1 (una
 *                             sola consulta para todos los planes listados).
 * @param medicamentosActivos  cantidad de {@link DetallePlan} {@code ACTIVO}
 *                             del plan, resuelta por el servicio con una
 *                             consulta agrupada (nunca una por plan).
 */
public record RespuestaPlan(long idPlan, long idGrupo, long idAdultoMayor, String nombreAdulto, String nombrePlan,
        LocalDate vigenteDesde, LocalDate vigenteHasta, long idEstado, long medicamentosActivos,
        OffsetDateTime fechaCreacion, OffsetDateTime fechaActualizacion) {

    public static RespuestaPlan desde(PlanMedicamentos plan, String nombreAdulto, long medicamentosActivos) {
        return new RespuestaPlan(plan.getIdPlan(), plan.getIdGrupo(), plan.getIdAdultoMayor(), nombreAdulto,
                plan.getNombrePlan(), plan.getVigenteDesde(), plan.getVigenteHasta(), plan.getIdEstado(),
                medicamentosActivos, plan.getFechaCreacion(), plan.getFechaActualizacion());
    }
}
