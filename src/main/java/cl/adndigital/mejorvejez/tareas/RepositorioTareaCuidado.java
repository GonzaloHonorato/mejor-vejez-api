package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioTareaCuidado extends JpaRepository<TareaCuidado, Long> {

    /**
     * Lee la tarea con {@code SELECT ... FOR UPDATE}: serializa las
     * operaciones que la afectan (cambio de estado, confirmación,
     * cancelación, (re)asignación), igual que {@code
     * planificacion.RepositorioPlanMedicamentos#bloquearPorId}. Orden de
     * bloqueo de la Fase 4: adulto → plan → tarea, nunca al revés. Debe
     * llamarse dentro de una transacción; el bloqueo dura hasta que esta
     * termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TareaCuidado t WHERE t.idTarea = :idTarea")
    Optional<TareaCuidado> bloquearPorId(@Param("idTarea") long idTarea);

    /**
     * Lee y bloquea ({@code SELECT ... FOR UPDATE}) varias tareas en una sola
     * consulta, por id ascendente: el recálculo tras un cambio de plan
     * (regla 8) bloquea así las tareas futuras del detalle, con el plan ya
     * bloqueado (orden adulto → plan → tarea), antes de decidir con su estado
     * vigente si las cancela o les ajusta el texto. Debe llamarse dentro de
     * una transacción y antes de que esas tareas estén en la sesión (si ya
     * lo estuvieran, Hibernate conserva su estado en memoria).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TareaCuidado t WHERE t.idTarea IN :idsTarea ORDER BY t.idTarea")
    List<TareaCuidado> bloquearPorIds(@Param("idsTarea") Collection<Long> idsTarea);

    /** La tarea con esa clave de ocurrencia (regla 3 de la Fase 4), si ya existe. */
    Optional<TareaCuidado> findByClaveOcurrencia(String claveOcurrencia);

    /**
     * Agenda del grupo en el rango {@code [desde, hasta)} (desde incluido,
     * hasta excluido), con filtros opcionales por adulto, estado y
     * responsable asignado, ordenada por horario y luego por id (mismo
     * desempate que {@code RepositorioPlanMedicamentos}). {@code
     * idUsuarioAsignado} filtra por una {@link AsignacionTarea} {@code
     * ACTIVO} (id {@code idEstadoAsignacionActiva}) de ese usuario, no por
     * cualquier asignación histórica de la tarea.
     */
    @Query("SELECT t FROM TareaCuidado t WHERE t.idGrupo = :idGrupo "
            + "AND t.fechaHorario >= :desde AND t.fechaHorario < :hasta "
            + "AND (:idAdulto IS NULL OR t.idAdultoMayor = :idAdulto) "
            + "AND (:idEstado IS NULL OR t.idEstado = :idEstado) "
            + "AND (:idUsuarioAsignado IS NULL OR EXISTS ("
            + "    SELECT 1 FROM AsignacionTarea a WHERE a.idTarea = t.idTarea "
            + "    AND a.idEstado = :idEstadoAsignacionActiva AND a.idUsuarioAsignado = :idUsuarioAsignado)) "
            + "ORDER BY t.fechaHorario, t.idTarea")
    List<TareaCuidado> buscarAgenda(@Param("idGrupo") long idGrupo, @Param("desde") OffsetDateTime desde,
            @Param("hasta") OffsetDateTime hasta, @Param("idAdulto") Long idAdulto,
            @Param("idEstado") Long idEstado, @Param("idUsuarioAsignado") Long idUsuarioAsignado,
            @Param("idEstadoAsignacionActiva") long idEstadoAsignacionActiva);
}
