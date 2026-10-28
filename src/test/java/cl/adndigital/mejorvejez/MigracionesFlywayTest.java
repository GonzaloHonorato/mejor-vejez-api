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
        assertThat(contarFilas("CATALOGO_NEGOCIO")).isEqualTo(21);
        assertThat(contarFilas("ROL_GRUPO")).isEqualTo(2);
    }

    @Test
    void catalogoTieneEstadosDeEnvio() {
        assertThat(estadosDe("ENVIO_REPORTE")).containsExactly("PENDIENTE", "ENVIADO", "FALLIDO");
        assertThat(estadosDe("ENVIO_REPORTE_DESTINATARIO")).containsExactly("PENDIENTE", "ENVIADO", "FALLIDO");
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
