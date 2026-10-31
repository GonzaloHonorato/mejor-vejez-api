package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria de {@link GeneradorClaveTemporal}, sin Spring: genera la
 * contraseña temporal que recibe una cuenta nueva.
 */
class GeneradorClaveTemporalTest {

    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final GeneradorClaveTemporal generador = new GeneradorClaveTemporal();

    @Test
    void largoDoce() {
        assertThat(generador.generar()).hasSize(12);
    }

    @Test
    void soloAlfabetoSinAmbiguos() {
        String clave = generador.generar();
        assertThat(clave.chars().allMatch(caracter -> ALFABETO.indexOf(caracter) >= 0)).isTrue();
        // Caracteres ambiguos que el alfabeto excluye a propósito.
        assertThat(clave).doesNotContain("0").doesNotContain("O").doesNotContain("1")
                .doesNotContain("l").doesNotContain("I");
    }

    @Test
    void tieneMayusculaMinusculaYDigito() {
        IntStream.range(0, 1_000).forEach(i -> {
            String clave = generador.generar();
            assertThat(clave.chars().anyMatch(Character::isUpperCase)).isTrue();
            assertThat(clave.chars().anyMatch(Character::isLowerCase)).isTrue();
            assertThat(clave.chars().anyMatch(Character::isDigit)).isTrue();
        });
    }

    @Test
    void noSeRepite() {
        Set<String> claves = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> claves.add(generador.generar()));
        assertThat(claves).hasSize(1_000);
    }
}
