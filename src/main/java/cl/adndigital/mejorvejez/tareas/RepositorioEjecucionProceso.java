package cl.adndigital.mejorvejez.tareas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioEjecucionProceso extends JpaRepository<EjecucionProceso, Long> {

    /** Las últimas 20 corridas del tipo de proceso indicado, de la más reciente a la más antigua. */
    List<EjecucionProceso> findTop20ByTipoEjecucionOrderByFechaInicioDescIdEjecucionDesc(String tipoEjecucion);
}
