package cl.adndigital.mejorvejez.tareas;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Escenarios del generador de tareas (Tarea 3 de la Fase 4) sobre el Oracle
 * compartido: grupo con coordinador, adulto y plan ACTIVO (fixturas F1–F3),
 * más detalles con días, horas, vigencia y responsable arbitrarios por JDBC
 * (la {@code FixturaPlanes} solo crea LUNES 08:00). Cada prueba crea su
 * propio escenario y filtra por sus ids; nunca cuenta tablas completas.
 * Compartida por {@code ServicioGeneracionTareasTest} y la prueba del cambio
 * de horario, que vive en otra clase por tener otro reloj.
 */
class FixturaGeneracion {

    /**
     * Ids de usuario explícitos por sobre el rango cacheado de {@code Long}
     * (> 127) para el responsable. Rango propio (distinto del 900.000.000 de
     * {@code ServicioDetallesPlanTest}); la identidad de {@code USUARIO}
     * nunca llega tan alto en el contenedor de pruebas.
     */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(950_000_000L);

    private final JdbcTemplate jdbcTemplate;
    private final FixturaGrupos fixturaGrupos;
    private final FixturaModelo fixturaModelo;
    private final FixturaPlanes fixturaPlanes;

    FixturaGeneracion(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        this.fixturaModelo = new FixturaModelo(jdbcTemplate);
        this.fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    /** Grupo, coordinador (creador del plan), adulto y plan ACTIVO con la vigencia indicada. */
    Escenario escenario(LocalDate vigenteDesde, LocalDate vigenteHasta) {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlanConVigencia(idGrupo, idAdulto, idCoordinador, vigenteDesde,
                vigenteHasta);
        return new Escenario(uid, idCoordinador, idGrupo, idAdulto, idPlan);
    }

    /** Usuario con id explícito > 127, integrante del grupo con el rol COLABORADOR y el estado indicado. */
    long crearResponsable(long idGrupo, String estadoIntegrante) {
        return crearIntegranteConIdAlto(idGrupo, "COLABORADOR", estadoIntegrante).idUsuario();
    }

    /**
     * Usuario con id explícito > 127, integrante del grupo con el rol y el
     * estado indicados; devuelve también su uid, para autenticarse con él
     * (p. ej. un coordinador cuyo id se compara como actor del historial).
     */
    UsuarioAlto crearIntegranteConIdAlto(long idGrupo, String rol, String estadoIntegrante) {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        String uid = FixturaGrupos.uidUnico();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', ?)",
                idUsuario, uid, "fixtura-" + UUID.randomUUID() + "@tareas.mejorvejez.test",
                "Responsable fixtura", idEstado("USUARIO", "ACTIVO"));
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuario, rol, estadoIntegrante);
        return new UsuarioAlto(idUsuario, uid);
    }

    record UsuarioAlto(long idUsuario, String uid) {
    }

    /** Crea un usuario (administrador o no) y devuelve su uid, para autenticarse con él. */
    String crearUsuario(boolean administrador) {
        String uid = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uid, administrador);
        return uid;
    }

    /** Detalle ACTIVO del plan con los datos indicados, sus días ({@code DIA_SEMANA}) y horas. */
    long crearDetalle(Escenario e, Detalle datos) {
        long idMedicamento = fixturaPlanes.crearMedicamento(datos.nombre);
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = idOpcion("UNIDAD_DOSIS", datos.unidad);
        long idDetalle = insertarYObtenerId(
                "INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, "
                        + "CONCENTRACION, INSTRUCCIONES, DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, FECHA_INICIO, FECHA_FIN, "
                        + "ID_RESPONSABLE_PREDETERMINADO, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "ID_DETALLE_PLAN",
                e.idPlan(), idMedicamento, datos.nombre, idPresentacion, datos.concentracion, datos.instrucciones,
                datos.dosis, idUnidad, datos.fechaInicio, datos.fechaFin, datos.idResponsable,
                idEstado("DETALLE_PLAN", "ACTIVO"));
        for (String dia : datos.dias) {
            jdbcTemplate.update("INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (?, ?)",
                    idDetalle, idOpcion("DIA_SEMANA", dia));
        }
        for (LocalTime hora : datos.horas) {
            jdbcTemplate.update(
                    "INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) "
                            + "VALUES (?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS'))",
                    idDetalle, "1970-01-01 " + hora + ":00");
        }
        return idDetalle;
    }

    /** Cambia el estado ({@code ACTIVO} | {@code INACTIVO}) de una fila de {@code tabla} (dominio {@code tabla.ESTADO}). */
    void cambiarEstado(String tabla, String columnaId, long id, String codigo) {
        jdbcTemplate.update("UPDATE " + tabla + " SET ID_ESTADO = ? WHERE " + columnaId + " = ?",
                idEstado(tabla, codigo), id);
    }

    /** Tareas del detalle con su estado como código, por horario. */
    List<Map<String, Object>> tareas(long idDetalle) {
        return jdbcTemplate.queryForList(
                "SELECT t.ID_TAREA, t.TIPO_TAREA, t.CLAVE_OCURRENCIA, t.TITULO_TAREA, t.DESCRIPCION_TAREA, "
                        + "t.ID_GRUPO, t.ID_ADULTO_MAYOR, ce.CODIGO_ESTADO "
                        + "FROM TAREA_CUIDADO t JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO "
                        + "WHERE t.ID_DETALLE_PLAN = ? ORDER BY t.FECHA_HORARIO, t.ID_TAREA",
                idDetalle);
    }

    /** Horarios de las tareas del detalle, en orden. */
    List<OffsetDateTime> horariosTareas(long idDetalle) {
        return jdbcTemplate.queryForList(
                "SELECT FECHA_HORARIO FROM TAREA_CUIDADO WHERE ID_DETALLE_PLAN = ? ORDER BY FECHA_HORARIO",
                OffsetDateTime.class, idDetalle);
    }

    /** Ocurrencias del detalle con su estado como código, por horario. */
    List<Map<String, Object>> ocurrencias(long idDetalle) {
        return jdbcTemplate.queryForList(
                "SELECT o.ID_OCURRENCIA, o.CLAVE_OCURRENCIA, o.ID_TAREA, o.FECHA_HORARIO, ce.CODIGO_ESTADO "
                        + "FROM OCURRENCIA_PLANIFICADA o JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = o.ID_ESTADO "
                        + "WHERE o.ID_DETALLE_PLAN = ? ORDER BY o.FECHA_HORARIO, o.ID_OCURRENCIA",
                idDetalle);
    }

    /** Asignaciones de la tarea con su estado como código. */
    List<Map<String, Object>> asignaciones(long idTarea) {
        return jdbcTemplate.queryForList(
                "SELECT a.ID_USUARIO_ASIGNADO, a.ID_USUARIO_ASIGNADOR, a.MOTIVO, ce.CODIGO_ESTADO "
                        + "FROM ASIGNACION_TAREA a JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO "
                        + "WHERE a.ID_TAREA = ?",
                idTarea);
    }

    /** Fila de {@code EJECUCION_PROCESO} con su estado como código. */
    Map<String, Object> ejecucion(long idEjecucion) {
        return jdbcTemplate.queryForMap(
                "SELECT e.TIPO_EJECUCION, e.CANTIDAD_CREADA, e.CANTIDAD_ERRORES, e.RESUMEN_ERRORES, e.FECHA_FIN, "
                        + "ce.CODIGO_ESTADO FROM EJECUCION_PROCESO e "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = e.ID_ESTADO WHERE e.ID_EJECUCION = ?",
                idEjecucion);
    }

    long contarHistorial(long idTarea) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM HISTORIAL_ESTADO_TAREA WHERE ID_TAREA = ?",
                Long.class, idTarea);
    }

    long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private long idOpcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
    }

    private long insertarYObtenerId(String sql, String columnaId, Object... parametros) {
        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(sql, new String[] { columnaId });
            for (int i = 0; i < parametros.length; i++) {
                sentencia.setObject(i + 1, parametros[i]);
            }
            return sentencia;
        }, generadorDeClave);
        return generadorDeClave.getKey().longValue();
    }

    record Escenario(String uidCoordinador, long idCoordinador, long idGrupo, long idAdulto, long idPlan) {
    }

    /** Datos mutables de un detalle, con valores por defecto válidos (todos los días a las 08:00, dosis 1). */
    static final class Detalle {
        String nombre = "Paracetamol";
        String concentracion = "500 mg";
        String instrucciones = "Con comida";
        BigDecimal dosis = BigDecimal.ONE;
        String unidad = "COMPRIMIDO";
        List<String> dias = List.of("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO");
        List<LocalTime> horas = List.of(LocalTime.of(8, 0));
        LocalDate fechaInicio;
        LocalDate fechaFin;
        Long idResponsable;

        Detalle(LocalDate fechaInicio) {
            this.fechaInicio = fechaInicio;
        }
    }
}
