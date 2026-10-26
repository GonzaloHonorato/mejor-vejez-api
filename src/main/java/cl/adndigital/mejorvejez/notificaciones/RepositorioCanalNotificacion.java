package cl.adndigital.mejorvejez.notificaciones;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioCanalNotificacion
        extends JpaRepository<CanalNotificacionUsuario, CanalNotificacionUsuarioId> {

    /** Canales configurados por el usuario indicado (regla 17 de la Fase 5: {@code GET /api/yo/canales}). */
    List<CanalNotificacionUsuario> findByIdUsuario(long idUsuario);

    /**
     * Canales configurados por varios usuarios en una sola consulta (regla 8
     * de la Fase 5: resolver los canales operativos de los destinatarios de
     * una alerta, sin N+1).
     */
    List<CanalNotificacionUsuario> findByIdUsuarioIn(Collection<Long> idsUsuario);
}
