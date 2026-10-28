package cl.adndigital.mejorvejez.comun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Clave compartida entre el Core y los microservicios nuevos (Alertas,
 * Reportes) para autenticar las llamadas a {@code /api/interno/**}. Se lee
 * de {@code mejorvejez.interno.clave-servicio} ({@code CLAVE_SERVICIO} en el
 * entorno, igual en los tres servicios) y se compara en tiempo constante con
 * {@link MessageDigest#isEqual(byte[], byte[])}.
 *
 * <p>No es un {@code @Component}: la cadena interna de {@code
 * ConfiguracionSeguridad} resuelve la propiedad con {@code @Value} en su
 * propio método {@code @Bean} y construye esta clase directamente. Así no
 * agrega un bean nuevo que la cadena PÚBLICA (o cualquier otro contexto que
 * solo importe {@code ConfiguracionSeguridad}) tendría que proveer.
 *
 * <p>Si la variable no está configurada (vacía o en blanco), {@link
 * #coincideCon} siempre devuelve {@code false}: una clave recibida vacía o
 * en blanco NO debe coincidir con una clave configurada vacía o en blanco
 * (de lo contrario, olvidar configurar {@code CLAVE_SERVICIO}, o
 * configurarla solo con espacios, abriría las rutas internas a cualquiera
 * que mande la cabecera vacía o en blanco).
 */
public class ClaveServicio {

    private final String claveConfigurada;

    public ClaveServicio(String claveConfigurada) {
        this.claveConfigurada = claveConfigurada == null ? "" : claveConfigurada;
    }

    public boolean coincideCon(String claveRecibida) {
        if (claveConfigurada.isBlank() || claveRecibida == null || claveRecibida.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                claveConfigurada.getBytes(StandardCharsets.UTF_8),
                claveRecibida.getBytes(StandardCharsets.UTF_8));
    }
}
