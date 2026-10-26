package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioOcurrenciaPlanificada extends JpaRepository<OcurrenciaPlanificada, Long> {

    /** Cuántas ocurrencias tiene ya ese detalle para ese instante exacto (cualquier estado o revisión). */
    long countByIdDetallePlanAndFechaHorario(long idDetallePlan, OffsetDateTime fechaHorario);

    /**
     * Si el turno (detalle + instante) está ocupado, según la regla 4 de la
     * Fase 4: tiene una ocurrencia en un estado "abierto" ({@code
     * idsEstadoAbiertos}, normalmente {@code PLANIFICADA} y {@code ERROR}),
     * o tiene una ocurrencia {@code idEstadoTareaCreada} cuya tarea no está
     * en {@code idEstadoTareaCancelada}. Una sola consulta: no trae filas al
     * llamador para decidir en Java, para que la decisión quede dentro de la
     * transacción que bloquea el plan.
     */
    @Query("SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END FROM OcurrenciaPlanificada o "
            + "WHERE o.idDetallePlan = :idDetalle AND o.fechaHorario = :horario AND ("
            + "    o.idEstado IN :idsEstadoAbiertos "
            + "    OR (o.idEstado = :idEstadoTareaCreada AND EXISTS ("
            + "        SELECT 1 FROM TareaCuidado t "
            + "        WHERE t.idTarea = o.idTarea AND t.idEstado <> :idEstadoTareaCancelada))"
            + ")")
    boolean turnoOcupado(@Param("idDetalle") long idDetalle, @Param("horario") OffsetDateTime horario,
            @Param("idsEstadoAbiertos") List<Long> idsEstadoAbiertos,
            @Param("idEstadoTareaCreada") long idEstadoTareaCreada,
            @Param("idEstadoTareaCancelada") long idEstadoTareaCancelada);

    /** Ocurrencias del detalle posteriores al instante indicado, por horario ascendente (p. ej. para regenerar). */
    List<OcurrenciaPlanificada> findByIdDetallePlanAndFechaHorarioAfterOrderByFechaHorario(long idDetallePlan,
            OffsetDateTime fechaHorario);

    /**
     * Ids de las ocurrencias de la ventana {@code [desde, hasta)} que están
     * en alguno de los estados indicados (normalmente {@code PLANIFICADA} y
     * {@code ERROR}), filtradas por grupo cuando {@code idGrupo} no es
     * {@code null} (las pruebas del generador siempre deben pasarlo; la
     * corrida global es la única excepción, Fase 4 restricciones globales).
     * Ordenadas por horario: la fase B del generador las procesa en ese
     * orden, una transacción {@code REQUIRES_NEW} por ocurrencia.
     */
    @Query("SELECT o.idOcurrencia FROM OcurrenciaPlanificada o "
            + "WHERE o.fechaHorario >= :desde AND o.fechaHorario < :hasta AND o.idEstado IN :idsEstado "
            + "AND (:idGrupo IS NULL OR o.idGrupo = :idGrupo) ORDER BY o.fechaHorario")
    List<Long> idsPorProcesar(@Param("desde") OffsetDateTime desde, @Param("hasta") OffsetDateTime hasta,
            @Param("idsEstado") List<Long> idsEstado, @Param("idGrupo") Long idGrupo);
}
