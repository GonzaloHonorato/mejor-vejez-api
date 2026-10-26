package cl.adndigital.mejorvejez.tareas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioHistorialEstadoTarea extends JpaRepository<HistorialEstadoTarea, Long> {

    /** Historial de la tarea, del cambio más antiguo al más reciente ({@code IdHistorialAsc} desempata). */
    List<HistorialEstadoTarea> findByIdTareaOrderByFechaCambioAscIdHistorialAsc(long idTarea);
}
