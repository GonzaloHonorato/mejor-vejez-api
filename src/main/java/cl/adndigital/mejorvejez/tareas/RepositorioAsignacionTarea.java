package cl.adndigital.mejorvejez.tareas;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioAsignacionTarea extends JpaRepository<AsignacionTarea, Long> {

    /** La asignación de la tarea en el estado indicado (p. ej. la única ACTIVO, por {@code UX_ASIGNACION_ACTIVA}). */
    Optional<AsignacionTarea> findByIdTareaAndIdEstado(long idTarea, long idEstado);

    /** Las asignaciones en el estado indicado de cualquiera de las tareas dadas, en una sola consulta (sin N+1). */
    List<AsignacionTarea> findByIdTareaInAndIdEstado(Collection<Long> idsTarea, long idEstado);

    /** Todas las asignaciones de la tarea (historial de asignación/reasignación), por orden de creación. */
    List<AsignacionTarea> findByIdTareaOrderByFechaAsignacionAscIdAsignacionAsc(long idTarea);
}
