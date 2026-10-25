package cl.adndigital.mejorvejez.adultos;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioAdultoMayor extends JpaRepository<AdultoMayor, Long> {

    /** Adultos mayores del grupo (en cualquier estado), por nombre ({@code GET /api/grupos/{idGrupo}/adultos}). */
    List<AdultoMayor> findByIdGrupoOrderByNombreCompleto(long idGrupo);

    /** Adultos mayores del grupo en el estado indicado, por nombre (p.ej. solo los ACTIVOS). */
    List<AdultoMayor> findByIdGrupoAndIdEstadoOrderByNombreCompleto(long idGrupo, long idEstado);

    /** Si ya existe un adulto con ese código interno en el grupo ({@code UQ_ADULTO_GRUPO_CODIGO}). */
    boolean existsByIdGrupoAndCodigoInterno(long idGrupo, String codigoInterno);

    /**
     * Lee el adulto mayor con {@code SELECT ... FOR UPDATE}: serializa las
     * operaciones que afectan el contacto principal del mismo adulto (p.ej.
     * dos designaciones concurrentes), igual que {@code
     * grupos.RepositorioGrupoFamiliar#bloquearPorId} serializa las del grupo.
     * Debe llamarse dentro de una transacción; el bloqueo dura hasta que esta
     * termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AdultoMayor a WHERE a.idAdultoMayor = :idAdulto")
    Optional<AdultoMayor> bloquearPorId(@Param("idAdulto") long idAdulto);
}
