package cl.adndigital.mejorvejez.alertas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioIntentoNotificacion extends JpaRepository<IntentoNotificacion, Long> {

    /** Historial de intentos de una alerta, en el orden en que se enviaron (regla 9 de la Fase 5). */
    List<IntentoNotificacion> findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(long idAlerta);
}
