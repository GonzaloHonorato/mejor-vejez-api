package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Prueba que {@link SolicitudCorreoRecuperarClave#toString()} nunca muestra el enlace. */
class SolicitudCorreoRecuperarClaveTest {

    @Test
    void toStringEnmascaraElEnlace() {
        SolicitudCorreoRecuperarClave solicitud = new SolicitudCorreoRecuperarClave("Ana", "ana@correo.cl",
                "https://app.mejorvejez.test/restablecer-clave?codigo=secreto-123");

        String texto = solicitud.toString();

        assertThat(texto).contains("enlace=***").doesNotContain("secreto-123").doesNotContain("restablecer-clave");
    }

    @Test
    void toStringConEnlaceNuloLoMuestraComoNulo() {
        SolicitudCorreoRecuperarClave solicitud = new SolicitudCorreoRecuperarClave("Ana", "ana@correo.cl", null);

        assertThat(solicitud.toString()).contains("enlace=null");
    }
}
