package cl.adndigital.mejorvejez;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConfiguracionTestcontainers.class)
class MigracionesFlywayTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void creaLas29Tablas() {
        Integer tablas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME <> 'flyway_schema_history'", Integer.class);

        // 27 de V1 + ENVIO_REPORTE y ENVIO_REPORTE_DESTINATARIO de V5.
        assertThat(tablas).isEqualTo(29);
    }

    @Test
    void cargaLosCatalogos() {
        // 48 de V2 + 6 de V5 (ENVIO_REPORTE.ESTADO y ENVIO_REPORTE_DESTINATARIO.ESTADO).
        assertThat(contarFilas("CATALOGO_ESTADOS")).isEqualTo(54);
        // 21 de V2 + 15 de PARENTESCO en V6 (formas femeninas separadas).
        assertThat(contarFilas("CATALOGO_NEGOCIO")).isEqualTo(36);
        assertThat(contarFilas("ROL_GRUPO")).isEqualTo(2);
    }

    @Test
    void catalogoTieneEstadosDeEnvio() {
        assertThat(estadosDe("ENVIO_REPORTE")).containsExactly("PENDIENTE", "ENVIADO", "FALLIDO");
        assertThat(estadosDe("ENVIO_REPORTE_DESTINATARIO")).containsExactly("PENDIENTE", "ENVIADO", "FALLIDO");
    }

    @Test
    void catalogoTieneLosParentescosEnOrden() {
        List<String> parentescos = jdbcTemplate.queryForList(
                "SELECT CODIGO || ':' || DESCRIPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = 'PARENTESCO' "
                        + "AND ACTIVO = 'S' ORDER BY ORDEN",
                String.class);

        assertThat(parentescos).containsExactly("PADRE:Padre", "MADRE:Madre", "HIJO:Hijo", "HIJA:Hija",
                "NIETO:Nieto", "NIETA:Nieta", "HERMANO:Hermano", "HERMANA:Hermana", "TIO:Tío", "TIA:Tía",
                "SOBRINO:Sobrino", "SOBRINA:Sobrina", "CONYUGE:Cónyuge", "YERNO:Yerno", "NUERA:Nuera");
    }

    @Test
    void contactoTieneIdParentescoNuloConFkAlCatalogo() {
        String nulable = jdbcTemplate.queryForObject(
                "SELECT NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'CONTACTO_EMERGENCIA' "
                        + "AND COLUMN_NAME = 'ID_PARENTESCO'",
                String.class);
        String tablaReferida = jdbcTemplate.queryForObject(
                "SELECT r.TABLE_NAME FROM USER_CONSTRAINTS c "
                        + "JOIN USER_CONSTRAINTS r ON r.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME "
                        + "WHERE c.CONSTRAINT_NAME = 'FK_CONTACTO_PARENTESCO'",
                String.class);

        assertThat(nulable).isEqualTo("Y");
        assertThat(tablaReferida).isEqualTo("CATALOGO_NEGOCIO");
    }

    @Test
    void creaLosIndicesUnicosPorEstado() {
        List<String> indices = jdbcTemplate.queryForList("SELECT INDEX_NAME FROM USER_INDEXES", String.class);

        assertThat(indices).contains("UX_CONTACTO_PRINCIPAL_ACTIVO", "UX_ASIGNACION_ACTIVA",
                "UX_PROPUESTA_ACTIVA_GRUPO", "UX_MEDICAMENTO_NORMALIZADO");
    }

    @Test
    void creaLosTriggersDeInmutabilidad() {
        List<String> triggers = jdbcTemplate.queryForList("SELECT TRIGGER_NAME FROM USER_TRIGGERS", String.class);

        assertThat(triggers).contains("TRG_AUDITORIA_INMUTABLE", "TRG_HISTORIAL_TAREA_INMUTABLE");
    }

    private Integer contarFilas(String tabla) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabla, Integer.class);
    }

    private List<String> estadosDe(String tabla) {
        return jdbcTemplate.queryForList(
                "SELECT CODIGO_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' "
                        + "ORDER BY ORDEN",
                String.class, tabla);
    }
}
