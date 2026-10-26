package cl.adndigital.mejorvejez.planificacion;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioPlanMedicamentos extends JpaRepository<PlanMedicamentos, Long> {

    /**
     * Planes del grupo (en cualquier estado), del más reciente al más
     * antiguo ({@code GET .../grupos/{idGrupo}/planes}). {@code IdPlanDesc}
     * es un desempate: dos planes creados en la misma transacción (o con la
     * resolución del reloj de la BD) pueden compartir {@code
     * FECHA_CREACION}, y sin un segundo criterio el orden entre ellos
     * quedaría indefinido.
     */
    List<PlanMedicamentos> findByIdGrupoOrderByFechaCreacionDescIdPlanDesc(long idGrupo);

    /** Planes del grupo para el adulto mayor indicado, del más reciente al más antiguo (mismo desempate). */
    List<PlanMedicamentos> findByIdGrupoAndIdAdultoMayorOrderByFechaCreacionDescIdPlanDesc(long idGrupo,
            long idAdultoMayor);

    /**
     * Lee el plan con {@code SELECT ... FOR UPDATE}: serializa las
     * operaciones que afectan el mismo plan (p. ej. acortar su vigencia a la
     * vez que se agrega un detalle), igual que {@code
     * adultos.RepositorioAdultoMayor#bloquearPorId}. Debe llamarse dentro de
     * una transacción; el bloqueo dura hasta que esta termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlanMedicamentos p WHERE p.idPlan = :idPlan")
    Optional<PlanMedicamentos> bloquearPorId(@Param("idPlan") long idPlan);
}
