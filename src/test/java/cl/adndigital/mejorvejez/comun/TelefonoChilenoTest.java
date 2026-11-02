package cl.adndigital.mejorvejez.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regla D2 del teléfono chileno: {@code +56} y 9 dígitos, con o sin el
 * prefijo, con espacios o guiones; se guarda como {@code +56XXXXXXXXX}.
 */
class TelefonoChilenoTest {

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest(name = "\"{0}\" se normaliza a {1}")
    @CsvSource(delimiter = '|', value = {
            "+56 9 1234 5678   | +56912345678",
            "912345678         | +56912345678",
            "+56912345678      | +56912345678",
            "9 1234 5678       | +56912345678",
            "9-1234-5678       | +56912345678",
            "+56-9-1234-5678   | +56912345678",
            "56912345678       | +56912345678",
            "'  +56 2 2345 6789 ' | +56223456789",
    })
    void normalizaLosFormatosAceptados(String entrada, String esperado) {
        assertThat(TelefonoChileno.esValido(entrada)).isTrue();
        assertThat(TelefonoChileno.normalizar(entrada)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" no es un teléfono chileno")
    @ValueSource(strings = {
            "12345678",
            "1234567890",
            "+5691234567",
            "+569123456789",
            "+54 9 11 2345 6789",
            "+1 555 123 4567",
            "abc",
            "9123a5678",
            "+56 (9) 1234 5678",
            "9.1234.5678",
            "++56912345678",
            "+ 56912345678x",
    })
    void rechazaLosFormatosInvalidos(String entrada) {
        assertThat(TelefonoChileno.esValido(entrada)).isFalse();
        assertThat(TelefonoChileno.normalizar(entrada)).isNull();
    }

    @Test
    void elEspacioDuroSeTrataComoUnEspacio() {
        assertThat(TelefonoChileno.normalizar("+56\u00A09\u00A01234\u00A05678")).isEqualTo("+56912345678");
        assertThat(TelefonoChileno.normalizar("\u00A0912345678\u2007")).isEqualTo("+56912345678");
    }

    @Test
    void mismoTelefonoComparaEnCrudoYNormalizado() {
        assertThat(TelefonoChileno.mismoTelefono("+1 555 123 4567", "+1 555 123 4567")).isTrue();
        assertThat(TelefonoChileno.mismoTelefono(" +1 555 123 4567 ", "+1 555 123 4567")).isTrue();
        assertThat(TelefonoChileno.mismoTelefono("9 1234 5678", "+56912345678")).isTrue();
        assertThat(TelefonoChileno.mismoTelefono("+56912345678", "+56 9 1234 5678")).isTrue();
        assertThat(TelefonoChileno.mismoTelefono("+56912345679", "+56912345678")).isFalse();
        assertThat(TelefonoChileno.mismoTelefono("+1 555 123 4567", null)).isFalse();
        assertThat(TelefonoChileno.mismoTelefono(null, "+56912345678")).isFalse();
    }

    @Test
    void aGuardarConservaElTelefonoActualSiNoCambiaYValidaSiCambia() {
        assertThat(TelefonoChileno.aGuardar("+1 555 123 4567", "+1 555 123 4567")).isEqualTo("+1 555 123 4567");
        assertThat(TelefonoChileno.aGuardar("9 1234 5678", "+1 555 123 4567")).isEqualTo("+56912345678");
        assertThat(TelefonoChileno.aGuardar("", "+1 555 123 4567")).isNull();
        assertThat(TelefonoChileno.aGuardar(null, "+56912345678")).isNull();

        ExcepcionCampoInvalido excepcion = org.junit.jupiter.api.Assertions.assertThrows(
                ExcepcionCampoInvalido.class, () -> TelefonoChileno.aGuardar("12345", "+1 555 123 4567"));
        assertThat(excepcion.getCampo()).isEqualTo("telefono");
        assertThat(excepcion.getMessage()).isEqualTo(TelefonoChileno.MENSAJE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void vacioNoEsValidoNiSeNormaliza(String entrada) {
        assertThat(TelefonoChileno.esValido(entrada)).isFalse();
        assertThat(TelefonoChileno.normalizar(entrada)).isNull();
    }

    @Test
    void normalizarSiEsValidoConservaElTextoInvalidoParaQueLaValidacionLoRechace() {
        assertThat(TelefonoChileno.normalizarSiEsValido("+56 9 1234 5678")).isEqualTo("+56912345678");
        assertThat(TelefonoChileno.normalizarSiEsValido(" abc ")).isEqualTo("abc");
        assertThat(TelefonoChileno.normalizarSiEsValido(null)).isNull();
        assertThat(TelefonoChileno.normalizarSiEsValido("")).isEmpty();
    }

    @Test
    void laAnotacionAceptaNuloYVacioYRechazaInvalidosConElMensajeDeLaRegla() {
        assertThat(validador.validate(new ConTelefono(null))).isEmpty();
        assertThat(validador.validate(new ConTelefono(""))).isEmpty();
        assertThat(validador.validate(new ConTelefono("+56912345678"))).isEmpty();

        Set<jakarta.validation.ConstraintViolation<ConTelefono>> violaciones =
                validador.validate(new ConTelefono("12345"));
        assertThat(violaciones).singleElement()
                .extracting(jakarta.validation.ConstraintViolation::getMessage)
                .isEqualTo("Ingresa un teléfono chileno: +56 y 9 dígitos.");
        assertThat(TelefonoChileno.MENSAJE).isEqualTo("Ingresa un teléfono chileno: +56 y 9 dígitos.");
    }

    private record ConTelefono(@TelefonoChilenoValido String telefono) {
    }
}
