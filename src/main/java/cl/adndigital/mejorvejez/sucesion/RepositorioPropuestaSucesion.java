package cl.adndigital.mejorvejez.sucesion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Propuestas de sucesión. Toda lectura que vaya a mutar una propuesta debe
 * hacerse <b>después</b> de bloquear el grupo ({@code
 * RepositorioGrupoFamiliar#bloquearPorId}): así la entidad entra al
 * contexto de persistencia con los datos ya confirmados por la transacción
 * anterior (lección de la Fase 5: bloquear y luego releer una entidad que ya
 * estaba en la sesión devuelve la foto vieja).
 */
public interface RepositorioPropuestaSucesion extends JpaRepository<PropuestaSucesion, Long> {

    /** La propuesta {@code idPropuesta} si pertenece a {@code idGrupo}; vacío si no existe o es de otro grupo. */
    @Query("SELECT p FROM PropuestaSucesion p WHERE p.idPropuesta = :idPropuesta AND p.idGrupo = :idGrupo")
    Optional<PropuestaSucesion> buscarDelGrupo(@Param("idGrupo") long idGrupo,
            @Param("idPropuesta") long idPropuesta);

    /**
     * La propuesta del grupo en alguno de los estados indicados (los activos:
     * {@code PENDIENTE} y {@code ACEPTADA}). {@code UX_PROPUESTA_ACTIVA_GRUPO}
     * garantiza que haya a lo sumo una.
     */
    Optional<PropuestaSucesion> findFirstByIdGrupoAndIdEstadoIn(long idGrupo, List<Long> idsEstado);

    /** Historial del grupo, de la más reciente a la más antigua ({@code fechaCreacion}, luego {@code idPropuesta}). */
    List<PropuestaSucesion> findByIdGrupoOrderByFechaCreacionDescIdPropuestaDesc(long idGrupo);
}
