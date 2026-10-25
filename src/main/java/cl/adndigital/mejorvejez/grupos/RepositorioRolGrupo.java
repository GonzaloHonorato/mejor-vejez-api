package cl.adndigital.mejorvejez.grupos;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioRolGrupo extends JpaRepository<RolGrupo, Long> {

    Optional<RolGrupo> findByNombreRol(String nombreRol);

    /** Todos los roles en orden de ID (el de siembra): los lista {@code GET /api/grupos/roles}. */
    List<RolGrupo> findAllByOrderByIdRolAsc();
}
