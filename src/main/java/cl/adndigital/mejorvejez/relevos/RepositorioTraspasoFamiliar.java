package cl.adndigital.mejorvejez.relevos;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioTraspasoFamiliar extends JpaRepository<TraspasoFamiliar, Long> {

    /**
     * Relevos del grupo, con filtro opcional por adulto, ordenados por
     * {@code fechaTraspaso} y luego {@code idTraspaso}, descendentes
     * (regla 1 de la Fase 7). {@code idAdulto} nulo no filtra.
     */
    @Query("SELECT t FROM TraspasoFamiliar t WHERE t.idGrupo = :idGrupo "
            + "AND (:idAdulto IS NULL OR t.idAdultoMayor = :idAdulto) "
            + "ORDER BY t.fechaTraspaso DESC, t.idTraspaso DESC")
    Page<TraspasoFamiliar> buscar(@Param("idGrupo") long idGrupo, @Param("idAdulto") Long idAdulto,
            Pageable pageable);
}
