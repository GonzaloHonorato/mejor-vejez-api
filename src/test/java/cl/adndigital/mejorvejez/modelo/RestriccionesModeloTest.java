package cl.adndigital.mejorvejez.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private long crearDetallePlan(long idGrupo, long idAdulto, long idUsuario) {
        long idEstadoPlanActivo = fixtura.idEstado("PLAN_MEDICAMENTOS", "ESTADO", "ACTIVO");
        long idEstadoDetalleActivo = fixtura.idEstado("DETALLE_PLAN", "ESTADO", "ACTIVO");
        long idPresentacion = fixtura.idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidadDosis = fixtura.idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        long idMedicamento = fixtura.crearMedicamento("Medicamento fixtura horario", "100 mg");

        jdbcTemplate.update(
                "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, "
                        + "VIGENTE_DESDE, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, TRUNC(SYSDATE), ?)",
                idGrupo, idAdulto, "Plan fixtura", idEstadoPlanActivo, idUsuario);
        long idPlan = jdbcTemplate.queryForObject(
                "SELECT MAX(ID_PLAN) FROM PLAN_MEDICAMENTOS WHERE ID_GRUPO = ?", Long.class, idGrupo);

        jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, "
                        + "DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, FECHA_INICIO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 1, ?, TRUNC(SYSDATE), ?)",
                idPlan, idMedicamento, "Medicamento fixtura horario", idPresentacion, idUnidadDosis,
                idEstadoDetalleActivo);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_DETALLE_PLAN) FROM DETALLE_PLAN WHERE ID_PLAN = ?", Long.class, idPlan);
    }
}
