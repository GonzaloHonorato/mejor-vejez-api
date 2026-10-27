package cl.adndigital.mejorvejez.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria (sin Spring) de {@link FiltroDatosSensibles} (regla 12 de
 * la Fase 7): no toca la base de datos ni necesita Testcontainers.
 */
class FiltroDatosSensiblesTest {

    @Test
    void quitaClavesSensiblesDeNivelSuperior() {
        Map<String, Object> origen = Map.of(
                "token", "secreto1",
                "Authorization", "secreto2",
                "clave", "secreto3",
                "contraseña", "secreto4",
                "idTokenFirebase", "secreto5",
                "idUsuario", 5,
                "motivo", "porque sí");

        Map<String, Object> filtrado = FiltroDatosSensibles.filtrar(origen);

        assertThat(filtrado).containsExactlyInAnyOrderEntriesOf(Map.of("idUsuario", 5, "motivo", "porque sí"));
    }

    @Test
    void quitaClavesSensiblesDeUnMapaAnidado() {
        Map<String, Object> origen = Map.of(
                "idUsuario", 5,
                "datos", Map.of("clave", "secreto", "idAdulto", 9));

        Map<String, Object> filtrado = FiltroDatosSensibles.filtrar(origen);

        assertThat(filtrado.get("idUsuario")).isEqualTo(5);
        assertThat(filtrado.get("datos")).isEqualTo(Map.of("idAdulto", 9));
    }

    @Test
    void quitaClavesSensiblesDentroDeUnaListaDeMapas() {
        Map<String, Object> origen = Map.of(
                "idUsuario", 5,
                "lista", List.of(Map.of("password", "secreto", "ok", 1), Map.of("nombre", "visible")));

        Map<String, Object> filtrado = FiltroDatosSensibles.filtrar(origen);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lista = (List<Map<String, Object>>) filtrado.get("lista");
        assertThat(lista.get(0)).isEqualTo(Map.of("ok", 1));
        assertThat(lista.get(1)).isEqualTo(Map.of("nombre", "visible"));
    }

    @Test
    void conservaClavesNoSensibles() {
        Map<String, Object> origen = Map.of("idUsuario", 5, "motivo", "porque sí");

        Map<String, Object> filtrado = FiltroDatosSensibles.filtrar(origen);

        assertThat(filtrado).containsExactlyInAnyOrderEntriesOf(origen);
    }

    @Test
    void unMapaNuloDaNulo() {
        assertThat(FiltroDatosSensibles.filtrar(null)).isNull();
    }
}
