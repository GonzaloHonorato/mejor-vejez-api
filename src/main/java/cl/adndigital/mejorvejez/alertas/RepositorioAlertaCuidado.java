package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioAlertaCuidado extends JpaRepository<AlertaCuidado, Long> {

    /**
     * Lee la alerta con {@code SELECT ... FOR UPDATE}: serializa su cierre y
     * la notificación de sus pendientes, igual que {@code
     * tareas.RepositorioTareaCuidado#bloquearPorId}. Debe llamarse dentro de
     * una transacción; el bloqueo dura hasta que esta termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AlertaCuidado a WHERE a.idAlerta = :idAlerta")
    Optional<AlertaCuidado> bloquearPorId(@Param("idAlerta") long idAlerta);

    /** La alerta con esa clave (regla 1 de la Fase 5: idempotencia), si ya existe. */
    Optional<AlertaCuidado> findByClaveAlerta(String claveAlerta);

    /**
     * Bandeja del grupo (TSA-47), con filtros opcionales por estado, tipo y
     * adulto, ordenada por fecha de detección y luego por id (ambos
     * descendente, regla 18 de la Fase 5).
     */
    @Query("SELECT a FROM AlertaCuidado a WHERE a.idGrupo = :idGrupo "
            + "AND (:idEstado IS NULL OR a.idEstado = :idEstado) "
            + "AND (:tipoAlerta IS NULL OR a.tipoAlerta = :tipoAlerta) "
            + "AND (:idAdulto IS NULL OR a.idAdultoMayor = :idAdulto) "
            + "ORDER BY a.fechaDeteccion DESC, a.idAlerta DESC")
    Page<AlertaCuidado> buscar(@Param("idGrupo") long idGrupo, @Param("idEstado") Long idEstado,
            @Param("tipoAlerta") String tipoAlerta, @Param("idAdulto") Long idAdulto, Pageable pageable);

    /**
     * Ids de las alertas ABIERTA detectadas desde {@code desde} (regla 10 de
     * la Fase 5: el paso "notificar pendientes" de cada corrida), por id,
     * para procesarlas en el mismo orden en que se crearon.
     */
    @Query("SELECT a.idAlerta FROM AlertaCuidado a WHERE a.idEstado = :idEstadoAbierta "
            + "AND a.fechaDeteccion >= :desde ORDER BY a.idAlerta")
    List<Long> idsAbiertasDesde(@Param("idEstadoAbierta") long idEstadoAbierta, @Param("desde") OffsetDateTime desde);
}
