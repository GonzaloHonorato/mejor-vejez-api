package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/**
 * {@code RESUMEN_ERRORES} es {@code VARCHAR2(2000)} con semántica BYTE: el
 * truncado debe medirse en bytes UTF-8 (los mensajes llevan tildes) y nunca
 * cortar un carácter multibyte por la mitad.
 */
class EjecucionProcesoTest {

    private static final OffsetDateTime AHORA = OffsetDateTime.parse("2026-11-02T00:05-03:00");

    @Test
    void resumenCortoNoSeToca() {
        assertThat(finalizarCon("Detalle 15: sin días u horas válidos"))
                .isEqualTo("Detalle 15: sin días u horas válidos");
        assertThat(finalizarCon(null)).isNull();
    }

    @Test
    void resumenAsciiSeTruncaA2000Bytes() {
        String resumen = finalizarCon("a".repeat(2500));

        assertThat(resumen).hasSize(2000);
    }

    @Test
    void resumenConTildesSeTruncaPorBytesYNoPorCaracteres() {
        // 1500 "á" = 1500 caracteres pero 3000 bytes en UTF-8.
        String resumen = finalizarCon("á".repeat(1500));

        assertThat(resumen.getBytes(StandardCharsets.UTF_8)).hasSize(2000);
        assertThat(resumen).isEqualTo("á".repeat(1000));
    }

    @Test
    void noCortaUnCaracterMultibytePorLaMitad() {
        // "a" + 1000 "á" = 2001 bytes: el último "á" no cabe entero y se descarta completo.
        String resumen = finalizarCon("a" + "á".repeat(1000));

        assertThat(resumen.getBytes(StandardCharsets.UTF_8)).hasSize(1999);
        assertThat(resumen).isEqualTo("a" + "á".repeat(999));
    }

    @Test
    void noCortaUnParSustitutoPorLaMitad() {
        // Emoji fuera del BMP: 2 chars Java, 4 bytes UTF-8. 1998 bytes + 4 > 2000.
        String resumen = finalizarCon("a".repeat(1998) + "😀" + "b");

        assertThat(resumen).isEqualTo("a".repeat(1998));
    }

    private static String finalizarCon(String resumen) {
        EjecucionProceso ejecucion = new EjecucionProceso(CodigosTareas.TIPO_EJECUCION_GENERACION, AHORA, AHORA,
                1L, AHORA);
        ejecucion.finalizar(2L, 0, 1, resumen, AHORA);
        return ejecucion.getResumenErrores();
    }
}
