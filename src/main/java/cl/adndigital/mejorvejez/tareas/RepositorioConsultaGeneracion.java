package cl.adndigital.mejorvejez.tareas;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;

/**
 * Consultas de solo lectura del generador de tareas que cruzan varias tablas
 * de otros módulos (planes, adultos, grupos) y devuelven solo ids, sin cargar
 * entidades. Los estados se resuelven con {@link ServicioCatalogos} y van
 * como parámetros enlazados, nunca como literales en el SQL. Dentro de una
 * transacción JPA, {@link NamedParameterJdbcTemplate} usa la misma conexión
 * (el {@code JpaTransactionManager} la expone a JDBC).
 */
@Repository
class RepositorioConsultaGeneracion {

    private static final String PLANES_CANDIDATOS = "SELECT p.ID_PLAN FROM PLAN_MEDICAMENTOS p "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = p.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = p.ID_GRUPO "
            + "WHERE p.ID_ESTADO = :planActivo AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND p.VIGENTE_DESDE <= :hasta AND (p.VIGENTE_HASTA IS NULL OR p.VIGENTE_HASTA >= :desde) "
            + "AND EXISTS (SELECT 1 FROM DETALLE_PLAN d WHERE d.ID_PLAN = p.ID_PLAN AND d.ID_ESTADO = :detalleActivo)";

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaGeneracion(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Ids de los planes ACTIVO, de un adulto ACTIVO y de un grupo ACTIVO,
     * cuya vigencia se cruza con {@code [desde, hasta]} (fechas calendario
     * inclusive) y que tienen al menos un detalle ACTIVO, ordenados por id.
     * {@code idGrupo} es opcional: {@code null} recorre todos los grupos (la
     * corrida programada); las pruebas siempre lo pasan (BD compartida).
     */
    List<Long> planesCandidatos(LocalDate desde, LocalDate hasta, Long idGrupo) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("planActivo", activo("PLAN_MEDICAMENTOS"))
                .addValue("adultoActivo", activo("ADULTO_MAYOR"))
                .addValue("grupoActivo", activo("GRUPO_FAMILIAR"))
                .addValue("detalleActivo", activo("DETALLE_PLAN"))
                .addValue("desde", desde)
                .addValue("hasta", hasta);
        String sql = PLANES_CANDIDATOS;
        if (idGrupo != null) {
            sql += " AND p.ID_GRUPO = :idGrupo";
            parametros.addValue("idGrupo", idGrupo);
        }
        return jdbc.queryForList(sql + " ORDER BY p.ID_PLAN", parametros, Long.class);
    }

    /**
     * Id del plan al que pertenece la ocurrencia (vía su detalle), sin cargar
     * entidades: la fase B lo usa para bloquear el plan ANTES de leer la
     * ocurrencia y el detalle (regla 6), para que esa lectura ya sea la
     * vigente tras el bloqueo y no una copia anterior cacheada en la sesión.
     * {@code ID_PLAN} de un detalle no cambia nunca.
     */
    Optional<Long> planDeOcurrencia(long idOcurrencia) {
        List<Long> ids = jdbc.queryForList(
                "SELECT d.ID_PLAN FROM OCURRENCIA_PLANIFICADA o "
                        + "JOIN DETALLE_PLAN d ON d.ID_DETALLE_PLAN = o.ID_DETALLE_PLAN "
                        + "WHERE o.ID_OCURRENCIA = :idOcurrencia",
                new MapSqlParameterSource("idOcurrencia", idOcurrencia), Long.class);
        return ids.stream().findFirst();
    }

    private long activo(String tabla) {
        return servicioCatalogos.idEstado(tabla, CodigosTareas.CAMPO_ESTADO, "ACTIVO");
    }
}
