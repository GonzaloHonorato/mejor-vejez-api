package cl.adndigital.mejorvejez.interno;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioEnvioReporte extends JpaRepository<EnvioReporte, Long> {

    /** El envío del grupo y período indicados, si ya existe ({@code UQ_ENVIO_REPORTE_GRUPO_PERIODO}). */
    Optional<EnvioReporte> findByIdGrupoAndInicioPeriodo(long idGrupo, LocalDate inicioPeriodo);

    /**
     * Lee el envío con {@code SELECT ... FOR UPDATE}: serializa dos {@code
     * PUT .../resultado} concurrentes del mismo envío, igual que {@code
     * alertas.RepositorioAlertaCuidado#bloquearPorId}. Debe llamarse dentro
     * de una transacción; el bloqueo dura hasta que esta termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EnvioReporte e WHERE e.idEnvio = :idEnvio")
    Optional<EnvioReporte> bloquearPorId(@Param("idEnvio") long idEnvio);
}
