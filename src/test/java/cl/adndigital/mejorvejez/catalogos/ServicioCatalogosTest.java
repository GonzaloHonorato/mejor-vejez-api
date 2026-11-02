package cl.adndigital.mejorvejez.catalogos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;

/**
 * Prueba {@link ServicioCatalogos} contra Oracle real (Testcontainers), con
 * el catálogo ya sembrado por {@code V2__catalogos.sql}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConfiguracionTestcontainers.class, ServicioCatalogos.class})
class ServicioCatalogosTest {

    @Autowired
    private ServicioCatalogos servicioCatalogos;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void idEstadoResuelvePendienteDeTarea() {
        long idEsperado = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? AND CODIGO_ESTADO = ?",
                Long.class, "TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        long idObtenido = servicioCatalogos.idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");

        assertThat(idObtenido).isEqualTo(idEsperado);
    }

    @Test
    void estadoDeOtroDominioSeRechaza() {
        long idActivoUsuario = servicioCatalogos.idEstado("USUARIO", "ESTADO", "ACTIVO");

        ExcepcionReglaNegocio excepcion = assertThrows(ExcepcionReglaNegocio.class,
                () -> servicioCatalogos.validarEstado(idActivoUsuario, "TAREA_CUIDADO", "ESTADO"));

        assertThat(excepcion.getMessage()).isEqualTo("El estado indicado no corresponde a TAREA_CUIDADO.ESTADO");
    }

    @Test
    void opcionDeOtroCatalogoSeRechaza() {
        long idLunes = jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, "DIA_SEMANA", "LUNES");

        ExcepcionReglaNegocio excepcion = assertThrows(ExcepcionReglaNegocio.class,
                () -> servicioCatalogos.validarOpcion(idLunes, TipoCatalogo.UNIDAD_DOSIS));

        assertThat(excepcion.getMessage())
                .isEqualTo("La opción indicada no pertenece al catálogo UNIDAD_DOSIS");
    }

    @Test
    void listarOpcionesDeUnidadDosisDevuelveOchoEnOrden() {
        List<RespuestaOpcion> opciones = servicioCatalogos.listarOpciones(TipoCatalogo.UNIDAD_DOSIS);

        assertThat(opciones).extracting(RespuestaOpcion::codigo)
                .containsExactly("COMPRIMIDO", "CAPSULA", "GOTA", "ML", "UNIDAD", "MG", "MCG", "UI");
    }

    @Test
    void listarOpcionesDeParentescoDevuelveQuinceEnOrden() {
        List<RespuestaOpcion> opciones = servicioCatalogos.listarOpciones(TipoCatalogo.PARENTESCO);

        assertThat(opciones).extracting(RespuestaOpcion::descripcion)
                .containsExactly("Padre", "Madre", "Hijo", "Hija", "Nieto", "Nieta", "Hermano", "Hermana", "Tío",
                        "Tía", "Sobrino", "Sobrina", "Cónyuge", "Yerno", "Nuera");
    }

    @Test
    void opcionValidaDevuelveLaOpcionDelTipo() {
        long idTio = idOpcion("PARENTESCO", "TIO");

        RespuestaOpcion opcion = servicioCatalogos.opcionValida(idTio, TipoCatalogo.PARENTESCO,
                "El parentesco indicado no es válido.");

        assertThat(opcion.codigo()).isEqualTo("TIO");
        assertThat(opcion.descripcion()).isEqualTo("Tío");
    }

    @Test
    void opcionValidaDeOtroTipoLanzaElMensajeIndicado() {
        long idLunes = idOpcion("DIA_SEMANA", "LUNES");

        ExcepcionReglaNegocio excepcion = assertThrows(ExcepcionReglaNegocio.class,
                () -> servicioCatalogos.opcionValida(idLunes, TipoCatalogo.PARENTESCO,
                        "El parentesco indicado no es válido."));

        assertThat(excepcion.getMessage()).isEqualTo("El parentesco indicado no es válido.");
    }

    @Test
    void opcionValidaInexistenteLanzaElMensajeIndicado() {
        ExcepcionReglaNegocio excepcion = assertThrows(ExcepcionReglaNegocio.class,
                () -> servicioCatalogos.opcionValida(-1L, TipoCatalogo.PARENTESCO,
                        "El parentesco indicado no es válido."));

        assertThat(excepcion.getMessage()).isEqualTo("El parentesco indicado no es válido.");
    }

    @Test
    void listarEstadosDevuelveSoloActivosOrdenadosPorOrden() {
        // Dentro de la transacción de la prueba (rollback automático al
        // terminar): marca CANCELADA inactiva para verificar que se excluye.
        jdbcTemplate.update(
                "UPDATE CATALOGO_ESTADOS SET ACTIVO = 'N' "
                        + "WHERE NOMBRE_TABLA = 'TAREA_CUIDADO' AND NOMBRE_CAMPO = 'ESTADO' "
                        + "AND CODIGO_ESTADO = 'CANCELADA'");

        List<String> codigosEsperados = jdbcTemplate.queryForList(
                "SELECT CODIGO_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'TAREA_CUIDADO' AND NOMBRE_CAMPO = 'ESTADO' AND ACTIVO = 'S' "
                        + "ORDER BY ORDEN",
                String.class);

        List<RespuestaEstado> estados = servicioCatalogos.listarEstados("TAREA_CUIDADO", "ESTADO");

        assertThat(estados).extracting(RespuestaEstado::codigo).containsExactlyElementsOf(codigosEsperados);
        assertThat(estados).extracting(RespuestaEstado::codigo).doesNotContain("CANCELADA");
    }

    private long idOpcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
    }
}
