package cl.adndigital.mejorvejez.catalogos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioCatalogoEstado extends JpaRepository<CatalogoEstado, Long> {

    /** Catálogo completo del dominio (activos e inactivos): lo usa la caché de {@code ServicioCatalogos}. */
    List<CatalogoEstado> findByNombreTablaAndNombreCampo(String nombreTabla, String nombreCampo);

    /** Solo los estados activos del dominio, en el orden de presentación. */
    List<CatalogoEstado> findByNombreTablaAndNombreCampoAndActivoTrueOrderByOrdenAsc(
            String nombreTabla, String nombreCampo);
}
