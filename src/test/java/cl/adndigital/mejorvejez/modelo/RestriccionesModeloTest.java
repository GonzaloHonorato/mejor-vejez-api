package cl.adndigital.mejorvejez.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;

/**
 * Verifica contra Oracle real (Testcontainers) que el esquema Flyway hace
 * cumplir las reglas del modelo que no son simples NOT NULL: unicidad activa
 * condicionada, FK compuestas antifuga entre grupos, catálogo normalizado sin
 * duplicados, inmutabilidad de auditoría y CHECKs de forma de dato.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConfiguracionTestcontainers.class)
class RestriccionesModeloTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaModelo fixtura;

    @BeforeEach
    void prepararFixtura() {
        fixtura = new FixturaModelo(jdbcTemplate);
    }

    @Test
    void contactoPrincipalActivoEsUnicoPorAdulto() {
        long idUsuario = fixtura.crearUsuario("uid-contacto-principal");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAdulto = fixtura.crearAdulto(idGrupo, idUsuario);
        long idEstadoActivo = fixtura.idEstado("CONTACTO_EMERGENCIA", "ESTADO", "ACTIVO");
        long idEstadoInactivo = fixtura.idEstado("CONTACTO_EMERGENCIA", "ESTADO", "INACTIVO");

        long idPrimerContacto = crearContacto(idAdulto, idUsuario, "S", idEstadoActivo);

        assertThrows(DuplicateKeyException.class,
                () -> crearContacto(idAdulto, idUsuario, "S", idEstadoActivo));

        jdbcTemplate.update("UPDATE CONTACTO_EMERGENCIA SET ID_ESTADO = ? WHERE ID_CONTACTO = ?",
                idEstadoInactivo, idPrimerContacto);

        long idSegundoContacto = crearContacto(idAdulto, idUsuario, "S", idEstadoActivo);

        assertThat(idSegundoContacto).isPositive();
    }

    @Test
    void unaSolaAsignacionActivaPorTarea() {
        long idUsuario = fixtura.crearUsuario("uid-asignacion-tarea");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAdulto = fixtura.crearAdulto(idGrupo, idUsuario);
        long idTarea = fixtura.crearTareaManual(idGrupo, idAdulto);
        long idEstadoActivo = fixtura.idEstado("ASIGNACION_TAREA", "ESTADO", "ACTIVO");
        long idEstadoReemplazada = fixtura.idEstado("ASIGNACION_TAREA", "ESTADO", "REEMPLAZADA");

        long idPrimeraAsignacion = crearAsignacion(idTarea, idGrupo, idUsuario, idEstadoActivo);

        assertThrows(DuplicateKeyException.class,
                () -> crearAsignacion(idTarea, idGrupo, idUsuario, idEstadoActivo));

        jdbcTemplate.update("UPDATE ASIGNACION_TAREA SET ID_ESTADO = ? WHERE ID_ASIGNACION = ?",
                idEstadoReemplazada, idPrimeraAsignacion);

        long idSegundaAsignacion = crearAsignacion(idTarea, idGrupo, idUsuario, idEstadoActivo);

        assertThat(idSegundaAsignacion).isPositive();
    }

    @Test
    void unaSolaPropuestaActivaPorGrupo() {
        long idUsuarioSaliente = fixtura.crearUsuario("uid-propuesta-saliente");
        long idUsuarioCandidato = fixtura.crearUsuario("uid-propuesta-candidato");
        long idGrupo = fixtura.crearGrupo(idUsuarioSaliente);
        long idEstadoPendiente = fixtura.idEstado("PROPUESTA_SUCESION", "ESTADO", "PENDIENTE");
        long idEstadoAceptada = fixtura.idEstado("PROPUESTA_SUCESION", "ESTADO", "ACEPTADA");
        long idEstadoRechazada = fixtura.idEstado("PROPUESTA_SUCESION", "ESTADO", "RECHAZADA");

        long idPrimeraPropuesta = crearPropuesta(idGrupo, idUsuarioSaliente, idUsuarioCandidato, idEstadoPendiente);

        assertThrows(DuplicateKeyException.class,
                () -> crearPropuesta(idGrupo, idUsuarioSaliente, idUsuarioCandidato, idEstadoAceptada));

        jdbcTemplate.update("UPDATE PROPUESTA_SUCESION SET ID_ESTADO = ? WHERE ID_PROPUESTA = ?",
                idEstadoRechazada, idPrimeraPropuesta);

        long idSegundaPropuesta = crearPropuesta(idGrupo, idUsuarioSaliente, idUsuarioCandidato, idEstadoPendiente);

        assertThat(idSegundaPropuesta).isPositive();
    }

    @Test
    void tareaNoAceptaAdultoDeOtroGrupo() {
        long idUsuario = fixtura.crearUsuario("uid-tarea-cruzada");
        long idGrupoA = fixtura.crearGrupo(idUsuario);
        long idGrupoB = fixtura.crearGrupo(idUsuario);
        long idAdultoDeGrupoA = fixtura.crearAdulto(idGrupoA, idUsuario);
        long idEstadoPendiente = fixtura.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, "
                        + "FECHA_HORARIO, ID_ESTADO) VALUES ('CITAS_CONTROLES_MEDICOS', ?, ?, ?, SYSTIMESTAMP, ?)",
                idGrupoB, idAdultoDeGrupoA, "Tarea con adulto de otro grupo", idEstadoPendiente));
    }

    /*
     * V8: tareas, ocurrencias y alertas que apuntan a un detalle de plan o a
     * una tarea deben ser del mismo adulto mayor y grupo. Ana y Pedro son del
     * mismo grupo, así que estas pruebas cubren el cruce entre adultos, que
     * las FK (ID_ADULTO_MAYOR, ID_GRUPO) hacia ADULTO_MAYOR no detectan.
     */

    @Test
    void detalleNoAceptaAdultoDistintoDelDeSuPlan() {
        long idUsuario = fixtura.crearUsuario("uid-detalle-cruzado");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAna = fixtura.crearAdulto(idGrupo, idUsuario);
        long idPedro = fixtura.crearAdulto(idGrupo, idUsuario);
        long idDetalleDeAna = crearDetallePlan(idGrupo, idAna, idUsuario);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE DETALLE_PLAN SET ID_ADULTO_MAYOR = ? WHERE ID_DETALLE_PLAN = ?", idPedro, idDetalleDeAna));
    }

    @Test
    void tareaNoAceptaDetalleDeOtroAdulto() {
        long idUsuario = fixtura.crearUsuario("uid-tarea-detalle-cruzado");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAna = fixtura.crearAdulto(idGrupo, idUsuario);
        long idPedro = fixtura.crearAdulto(idGrupo, idUsuario);
        long idDetalleDePedro = crearDetallePlan(idGrupo, idPedro, idUsuario);

        assertThrows(DataIntegrityViolationException.class,
                () -> crearTareaMedicamento("DET-CRUZADO-ANA", idGrupo, idAna, idDetalleDePedro));

        assertThat(crearTareaMedicamento("DET-PROPIO-PEDRO", idGrupo, idPedro, idDetalleDePedro)).isPositive();
    }

    @Test
    void ocurrenciaNoAceptaDetalleNiTareaDeOtroAdulto() {
        long idUsuario = fixtura.crearUsuario("uid-ocurrencia-cruzada");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAna = fixtura.crearAdulto(idGrupo, idUsuario);
        long idPedro = fixtura.crearAdulto(idGrupo, idUsuario);
        long idDetalleDeAna = crearDetallePlan(idGrupo, idAna, idUsuario);
        long idDetalleDePedro = crearDetallePlan(idGrupo, idPedro, idUsuario);
        long idTareaDePedro = crearTareaMedicamento("OCU-TAREA-PEDRO", idGrupo, idPedro, idDetalleDePedro);
        long idEstado = fixtura.idEstado("OCURRENCIA_PLANIFICADA", "ESTADO", "PLANIFICADA");
        String sql = "INSERT INTO OCURRENCIA_PLANIFICADA (ID_DETALLE_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, "
                + "FECHA_HORARIO, CLAVE_OCURRENCIA, ID_ESTADO, ID_TAREA) VALUES (?, ?, ?, SYSTIMESTAMP, ?, ?, ?)";

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                sql, idDetalleDePedro, idAna, idGrupo, "OCU-DETALLE-CRUZADO", idEstado, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                sql, idDetalleDeAna, idAna, idGrupo, "OCU-TAREA-CRUZADA", idEstado, idTareaDePedro));
    }

    @Test
    void alertaNoAceptaTareaNiDetalleDeOtroAdulto() {
        long idUsuario = fixtura.crearUsuario("uid-alerta-cruzada");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAna = fixtura.crearAdulto(idGrupo, idUsuario);
        long idPedro = fixtura.crearAdulto(idGrupo, idUsuario);
        long idTareaDePedro = fixtura.crearTareaManual(idGrupo, idPedro);
        long idDetalleDePedro = crearDetallePlan(idGrupo, idPedro, idUsuario);
        long idEstado = fixtura.idEstado("ALERTA_CUIDADO", "ESTADO", "ABIERTA");
        String sql = "INSERT INTO ALERTA_CUIDADO (CLAVE_ALERTA, TIPO_ALERTA, ID_TAREA, ID_DETALLE_PLAN, ID_GRUPO, "
                + "ID_ADULTO_MAYOR, MENSAJE_ALERTA, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(sql,
                "ATR-CRUZADA", "ATRASO_TAREA", idTareaDePedro, null, idGrupo, idAna, "Alerta cruzada", idEstado));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(sql,
                "REP-CRUZADA", "REPOSICION_MEDICAMENTO", null, idDetalleDePedro, idGrupo, idAna, "Alerta cruzada",
                idEstado));

        jdbcTemplate.update(sql, "ATR-PROPIA", "ATRASO_TAREA", idTareaDePedro, null, idGrupo, idPedro,
                "Alerta propia", idEstado);
    }

    @Test
    void medicamentoDuplicadoNormalizadoSeRechaza() {
        fixtura.crearMedicamento("Losartán", "50 mg");

        assertThrows(DuplicateKeyException.class,
                () -> fixtura.crearMedicamento(" losartán ", "50 MG "));
    }

    @Test
    void auditoriaNoSePuedeModificarNiEliminar() {
        long idResultadoExito = fixtura.idEstado("EVENTO_AUDITORIA", "RESULTADO", "EXITO");
        long idEvento = crearEventoAuditoria(idResultadoExito);

        DataAccessException excepcionUpdate = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE EVENTO_AUDITORIA SET CODIGO_ACCION = 'OTRA_ACCION' WHERE ID_EVENTO_AUDITORIA = ?",
                idEvento));
        assertThat(excepcionUpdate.getMessage()).contains("ORA-20010");

        DataAccessException excepcionDelete = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "DELETE FROM EVENTO_AUDITORIA WHERE ID_EVENTO_AUDITORIA = ?", idEvento));
        assertThat(excepcionDelete.getMessage()).contains("ORA-20010");
    }

    @Test
    void horaDeAdministracionDebeSerSoloHora() {
        long idUsuario = fixtura.crearUsuario("uid-hora-administracion");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAdulto = fixtura.crearAdulto(idGrupo, idUsuario);
        long idDetallePlan = crearDetallePlan(idGrupo, idAdulto, idUsuario);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) VALUES (?, TIMESTAMP '1970-01-02 08:00:00')",
                idDetallePlan));

        jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) VALUES (?, TIMESTAMP '1970-01-01 08:00:00')",
                idDetallePlan);

        Integer horasRegistradas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DETALLE_PLAN_HORARIO WHERE ID_DETALLE_PLAN = ?", Integer.class, idDetallePlan);
        assertThat(horasRegistradas).isEqualTo(1);
    }

    @Test
    void tareaDeMedicamentoExigeClaveYDetalle() {
        long idUsuario = fixtura.crearUsuario("uid-tarea-medicamento");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idAdulto = fixtura.crearAdulto(idGrupo, idUsuario);
        long idEstadoPendiente = fixtura.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, "
                        + "FECHA_HORARIO, ID_ESTADO) VALUES ('MEDICAMENTOS', ?, ?, ?, SYSTIMESTAMP, ?)",
                idGrupo, idAdulto, "Tarea de medicamento sin clave ni detalle", idEstadoPendiente));
    }

    @Test
    void envioReporteUnicoPorGrupoYPeriodo() {
        long idUsuario = fixtura.crearUsuario("uid-envio-reporte");
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idEstadoPendiente = fixtura.idEstado("ENVIO_REPORTE", "ESTADO", "PENDIENTE");
        LocalDate inicioPeriodo = LocalDate.of(2026, 8, 31);

        crearEnvioReporte(idGrupo, inicioPeriodo, idEstadoPendiente);

        assertThrows(DuplicateKeyException.class,
                () -> crearEnvioReporte(idGrupo, inicioPeriodo, idEstadoPendiente));
    }

    @Test
    void ejecucionAceptaTipoReporteSemanal() {
        long idEstadoIniciada = fixtura.idEstado("EJECUCION_PROCESO", "ESTADO", "INICIADA");

        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(
                    "INSERT INTO EJECUCION_PROCESO (TIPO_EJECUCION, INICIO_VENTANA, FIN_VENTANA, ID_ESTADO) "
                            + "VALUES ('REPORTE_SEMANAL', SYSTIMESTAMP, SYSTIMESTAMP, ?)",
                    new String[] { "ID_EJECUCION" });
            sentencia.setLong(1, idEstadoIniciada);
            return sentencia;
        }, generadorDeClave);
        long idEjecucion = generadorDeClave.getKey().longValue();

        // Filtra por la fila propia (la tabla es compartida entre pruebas): nunca se cuenta la tabla completa.
        String tipoGuardado = jdbcTemplate.queryForObject(
                "SELECT TIPO_EJECUCION FROM EJECUCION_PROCESO WHERE ID_EJECUCION = ?", String.class, idEjecucion);

        assertThat(tipoGuardado).isEqualTo("REPORTE_SEMANAL");
    }

    private long crearEnvioReporte(long idGrupo, LocalDate inicioPeriodo, long idEstado) {
        jdbcTemplate.update(
                "INSERT INTO ENVIO_REPORTE (ID_GRUPO, INICIO_PERIODO, FIN_PERIODO, ID_ESTADO) VALUES (?, ?, ?, ?)",
                idGrupo, Date.valueOf(inicioPeriodo), Date.valueOf(inicioPeriodo.plusDays(6)), idEstado);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_ENVIO) FROM ENVIO_REPORTE WHERE ID_GRUPO = ?", Long.class, idGrupo);
    }

    private long crearContacto(long idAdulto, long idCreador, String esPrincipal, long idEstado) {
        jdbcTemplate.update(
                "INSERT INTO CONTACTO_EMERGENCIA (ID_ADULTO_MAYOR, NOMBRE_CONTACTO, TELEFONO, ES_PRINCIPAL, "
                        + "ID_ESTADO, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, ?, ?)",
                idAdulto, "Contacto fixtura", "+56900000000", esPrincipal, idEstado, idCreador);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_CONTACTO) FROM CONTACTO_EMERGENCIA WHERE ID_ADULTO_MAYOR = ?", Long.class, idAdulto);
    }

    private long crearAsignacion(long idTarea, long idGrupo, long idUsuario, long idEstado) {
        jdbcTemplate.update(
                "INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, "
                        + "ID_ESTADO) VALUES (?, ?, ?, ?, ?)",
                idTarea, idGrupo, idUsuario, idUsuario, idEstado);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_ASIGNACION) FROM ASIGNACION_TAREA WHERE ID_TAREA = ?", Long.class, idTarea);
    }

    private long crearPropuesta(long idGrupo, long idUsuarioSaliente, long idUsuarioCandidato, long idEstado) {
        jdbcTemplate.update(
                "INSERT INTO PROPUESTA_SUCESION (ID_GRUPO, ID_USUARIO_SALIENTE, ID_USUARIO_CANDIDATO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?)",
                idGrupo, idUsuarioSaliente, idUsuarioCandidato, idEstado);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_PROPUESTA) FROM PROPUESTA_SUCESION WHERE ID_GRUPO = ?", Long.class, idGrupo);
    }

    private long crearEventoAuditoria(long idResultado) {
        jdbcTemplate.update(
                "INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, CODIGO_ACCION, "
                        + "ID_RESULTADO) VALUES (?, ?, ?, ?, ?)",
                "fixtura", "PRUEBA", "1", "ACCION_FIXTURA", idResultado);
        return jdbcTemplate.queryForObject("SELECT MAX(ID_EVENTO_AUDITORIA) FROM EVENTO_AUDITORIA", Long.class);
    }

    private long crearTareaMedicamento(String clave, long idGrupo, long idAdulto, long idDetalle) {
        KeyHolder llave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(
                    "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, "
                            + "ID_DETALLE_PLAN, TITULO_TAREA, FECHA_HORARIO, ID_ESTADO) "
                            + "VALUES ('MEDICAMENTOS', ?, ?, ?, ?, ?, SYSTIMESTAMP, ?)",
                    new String[] {"ID_TAREA"});
            sentencia.setString(1, clave);
            sentencia.setLong(2, idGrupo);
            sentencia.setLong(3, idAdulto);
            sentencia.setLong(4, idDetalle);
            sentencia.setString(5, "Tarea de medicamento");
            sentencia.setLong(6, fixtura.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE"));
            return sentencia;
        }, llave);
        return llave.getKey().longValue();
    }

    private long crearDetallePlan(long idGrupo, long idAdulto, long idUsuario) {
        long idEstadoPlanActivo = fixtura.idEstado("PLAN_MEDICAMENTOS", "ESTADO", "ACTIVO");
        long idEstadoDetalleActivo = fixtura.idEstado("DETALLE_PLAN", "ESTADO", "ACTIVO");
        long idPresentacion = fixtura.idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidadDosis = fixtura.idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        // Un medicamento por adulto: dos detalles en la misma prueba no chocan en UX_MEDICAMENTO_NORMALIZADO.
        String nombreMedicamento = "Medicamento fixtura horario " + idAdulto;
        long idMedicamento = fixtura.crearMedicamento(nombreMedicamento, "100 mg");

        jdbcTemplate.update(
                "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, "
                        + "VIGENTE_DESDE, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, TRUNC(SYSDATE), ?)",
                idGrupo, idAdulto, "Plan fixtura", idEstadoPlanActivo, idUsuario);
        long idPlan = jdbcTemplate.queryForObject(
                "SELECT MAX(ID_PLAN) FROM PLAN_MEDICAMENTOS WHERE ID_GRUPO = ?", Long.class, idGrupo);

        jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN (ID_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, "
                        + "ID_PRESENTACION, DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, FECHA_INICIO, ID_ESTADO) "
                        + "VALUES (?, (SELECT ID_ADULTO_MAYOR FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?), "
                        + "(SELECT ID_GRUPO FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?), ?, ?, ?, 1, ?, TRUNC(SYSDATE), ?)",
                idPlan, idPlan, idPlan, idMedicamento, nombreMedicamento, idPresentacion, idUnidadDosis,
                idEstadoDetalleActivo);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_DETALLE_PLAN) FROM DETALLE_PLAN WHERE ID_PLAN = ?", Long.class, idPlan);
    }
}
