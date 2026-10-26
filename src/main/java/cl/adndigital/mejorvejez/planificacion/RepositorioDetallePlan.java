package cl.adndigital.mejorvejez.planificacion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioDetallePlan extends JpaRepository<DetallePlan, Long> {

    /** Detalles del plan (en cualquier estado), por id de inserción ({@code GET .../planes/{idPlan}/detalles}). */
    List<DetallePlan> findByIdPlanOrderByIdDetallePlan(long idPlan);

    /** Cantidad de detalles del plan en el estado indicado (p. ej. cuántos quedan ACTIVOS). */
    long countByIdPlanAndIdEstado(long idPlan, long idEstado);

    /**
     * Cantidad de detalles {@code ACTIVO} de cada uno de los planes
     * indicados, en una sola consulta agrupada (nunca una {@code
     * countByIdPlanAndIdEstado} por plan): lo usa {@code ServicioPlanes} al
     * listar planes del grupo, para no incurrir en N+1.
     */
    @Query("SELECT d.idPlan AS idPlan, COUNT(d) AS cantidad FROM DetallePlan d "
            + "WHERE d.idPlan IN :idsPlan AND d.idEstado = :idEstado GROUP BY d.idPlan")
    List<ConteoDetallesActivos> contarActivosPorPlan(@Param("idsPlan") List<Long> idsPlan,
            @Param("idEstado") long idEstado);

    /** Proyección de {@link #contarActivosPorPlan}: cuántos detalles ACTIVO tiene cada plan. */
    interface ConteoDetallesActivos {
        Long getIdPlan();

        Long getCantidad();
    }
}
