package cl.adndigital.mejorvejez.grupos;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioIntegranteGrupo extends JpaRepository<IntegranteGrupo, Long> {

    /** Membresía (en cualquier estado) del usuario en el grupo. */
    Optional<IntegranteGrupo> findByIdGrupoAndIdUsuario(long idGrupo, long idUsuario);

    /** Integrantes del grupo (en cualquier estado), del más antiguo al más reciente. */
    List<IntegranteGrupo> findByIdGrupoOrderByFechaIncorporacion(long idGrupo);

    /** Membresías del usuario en el estado indicado (p.ej. sus grupos activos). */
    List<IntegranteGrupo> findByIdUsuarioAndIdEstado(long idUsuario, long idEstado);

    /** Cuántos integrantes del grupo tienen el rol y el estado indicados. */
    long countByIdGrupoAndIdRolAndIdEstado(long idGrupo, long idRol, long idEstado);
}
