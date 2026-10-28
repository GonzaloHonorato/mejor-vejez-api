package cl.adndigital.mejorvejez.alertas;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioIntentoNotificacion extends JpaRepository<IntentoNotificacion, Long> {

    /** Historial de intentos de una alerta, en el orden en que se enviaron (regla 9 de la Fase 5). */
    List<IntentoNotificacion> findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(long idAlerta);

    /**
     * El {@code ID_ALERTA} del intento, sin cargar la entidad completa (Fase
     * 9, TSA-61): {@code interno.ServicioReservaIntentos} la usa para
     * bloquear la alerta ANTES de leer el intento, así una reserva
     * concurrente que esté expirando pendientes huérfanos de la misma
     * alerta se serializa con {@code PUT /api/interno/intentos/{idIntento}}
     * (si se cargara el intento primero, quedaría en la caché de la sesión
     * y una relectura posterior no vería los cambios que confirmó la otra
     * transacción mientras esta esperaba el bloqueo).
     */
    @Query("SELECT i.idAlerta FROM IntentoNotificacion i WHERE i.idIntento = :idIntento")
    Optional<Long> idAlertaDe(@Param("idIntento") long idIntento);
}
