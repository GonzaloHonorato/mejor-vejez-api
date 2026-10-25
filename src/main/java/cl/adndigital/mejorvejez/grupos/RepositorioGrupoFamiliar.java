package cl.adndigital.mejorvejez.grupos;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioGrupoFamiliar extends JpaRepository<GrupoFamiliar, Long> {

    /** Todos los grupos, ordenados por nombre ({@code GET /api/grupos?todos=true}, solo administrador). */
    List<GrupoFamiliar> findAllByOrderByNombreGrupoAsc();

    /**
     * Lee el grupo con {@code SELECT ... FOR UPDATE}: serializa las
     * operaciones que cambian integrantes o roles del mismo grupo (p.ej. la
     * salida del último coordinador). Debe llamarse dentro de una
     * transacción; el bloqueo dura hasta que esta termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GrupoFamiliar g WHERE g.idGrupo = :idGrupo")
    Optional<GrupoFamiliar> bloquearPorId(@Param("idGrupo") long idGrupo);
}
