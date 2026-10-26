package cl.adndigital.mejorvejez.planificacion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso al catálogo global de medicamentos ({@code MEDICAMENTO}). {@link
 * #buscar(long, String)} es una consulta nativa (no hay una ruta JPQL
 * razonable para expresar el "usado en el grupo" calculado y el {@code
 * FETCH FIRST} de Oracle a la vez), igual de espíritu que {@code
 * adultos.RepositorioHistorialAdulto}.
 */
public interface RepositorioMedicamento extends JpaRepository<Medicamento, Long> {

    /** El medicamento, solo si está {@code ACTIVO}. Lo usa {@code ServicioMedicamentos#obtenerActivo}. */
    Optional<Medicamento> findByIdMedicamentoAndActivoTrue(long idMedicamento);

    /**
     * El medicamento cuyo {@code (NOMBRE, ID_PRESENTACION, CONCENTRACION)}
     * normalizado (mayúsculas, sin espacios en los extremos; concentración
     * nula = nula) coincide con el indicado. Red de la unicidad que impone
     * {@code UX_MEDICAMENTO_NORMALIZADO} (V3): el alta la consulta antes de
     * insertar, y vuelve a consultarla si el índice rechaza el {@code save}
     * por una alta concurrente.
     */
    @Query("""
            SELECT m FROM Medicamento m
            WHERE UPPER(TRIM(m.nombre)) = UPPER(TRIM(:nombre))
              AND m.idPresentacion = :idPresentacion
              AND ((:concentracion IS NULL AND m.concentracion IS NULL)
                   OR UPPER(TRIM(m.concentracion)) = UPPER(TRIM(:concentracion)))
            """)
    Optional<Medicamento> buscarNormalizado(@Param("nombre") String nombre,
            @Param("idPresentacion") Long idPresentacion, @Param("concentracion") String concentracion);

    /** 1 si existe un {@code DETALLE_PLAN} de un {@code PLAN_MEDICAMENTOS} del grupo que use el medicamento; si no, 0. */
    @Query(value = """
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM DETALLE_PLAN dp JOIN PLAN_MEDICAMENTOS pm ON pm.ID_PLAN = dp.ID_PLAN
                WHERE dp.ID_MEDICAMENTO = :idMedicamento AND pm.ID_GRUPO = :idGrupo
            ) THEN 1 ELSE 0 END FROM DUAL
            """, nativeQuery = true)
    int usadoEnGrupo(@Param("idMedicamento") long idMedicamento, @Param("idGrupo") long idGrupo);

    /**
     * Medicamentos {@code ACTIVO}, máximo 20: si {@code patron} es {@code
     * null} (sin texto de búsqueda), solo los ya usados en el grupo; si no,
     * los que calcen por nombre o principio activo, usados o no. Primero los
     * usados en el grupo, luego por nombre. {@code patron} ya viene armado
     * (mayúsculas, con {@code %} en los extremos y los comodines del
     * propio texto escapados) por {@code ServicioMedicamentos}.
     */
    @Query(value = """
            SELECT m.ID_MEDICAMENTO AS idMedicamento, m.NOMBRE AS nombre, m.PRINCIPIO_ACTIVO AS principioActivo,
                   m.ID_PRESENTACION AS idPresentacion, m.CONCENTRACION AS concentracion,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM DETALLE_PLAN dp JOIN PLAN_MEDICAMENTOS pm ON pm.ID_PLAN = dp.ID_PLAN
                       WHERE dp.ID_MEDICAMENTO = m.ID_MEDICAMENTO AND pm.ID_GRUPO = :idGrupo
                   ) THEN 1 ELSE 0 END AS usadoEnGrupo
            FROM MEDICAMENTO m
            WHERE m.ACTIVO = 'S'
              AND (
                    (:patron IS NULL AND EXISTS (
                        SELECT 1 FROM DETALLE_PLAN dp2 JOIN PLAN_MEDICAMENTOS pm2 ON pm2.ID_PLAN = dp2.ID_PLAN
                        WHERE dp2.ID_MEDICAMENTO = m.ID_MEDICAMENTO AND pm2.ID_GRUPO = :idGrupo
                    ))
                 OR (:patron IS NOT NULL AND (
                        UPPER(m.NOMBRE) LIKE :patron ESCAPE '\\'
                        OR UPPER(m.PRINCIPIO_ACTIVO) LIKE :patron ESCAPE '\\'
                    ))
                  )
            ORDER BY usadoEnGrupo DESC, m.NOMBRE
            FETCH FIRST 20 ROWS ONLY
            """, nativeQuery = true)
    List<FilaBusqueda> buscar(@Param("idGrupo") long idGrupo, @Param("patron") String patron);

    /** Proyección nativa de {@link #buscar(long, String)}. */
    interface FilaBusqueda {
        Long getIdMedicamento();

        String getNombre();

        String getPrincipioActivo();

        Long getIdPresentacion();

        String getConcentracion();

        Number getUsadoEnGrupo();
    }
}
