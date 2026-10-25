package cl.adndigital.mejorvejez.auditoria;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioEventoAuditoria extends JpaRepository<EventoAuditoria, Long> {

    /** Eventos con el ID de correlación indicado (lo usan las pruebas de integración). */
    List<EventoAuditoria> findByIdCorrelacion(String idCorrelacion);
}
