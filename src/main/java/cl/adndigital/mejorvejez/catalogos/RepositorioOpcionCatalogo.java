package cl.adndigital.mejorvejez.catalogos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioOpcionCatalogo extends JpaRepository<OpcionCatalogo, Long> {

    /** Solo las opciones activas del tipo indicado, en el orden de presentación. */
    List<OpcionCatalogo> findByTipoCatalogoAndActivoTrueOrderByOrdenAsc(TipoCatalogo tipoCatalogo);

    /** Todas las opciones del tipo indicado, activas e inactivas. */
    List<OpcionCatalogo> findByTipoCatalogo(TipoCatalogo tipoCatalogo);
}
