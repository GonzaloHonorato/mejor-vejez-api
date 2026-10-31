package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.firebase.FirebaseApp;

/**
 * Prueba unitaria de {@link ConfiguracionFirebase}, sin Spring y sin red:
 * cubre el camino de credencial malformada (503 configurado, sin filtrar su
 * contenido), la credencial de otro proyecto y la reutilización del {@link FirebaseApp} por nombre cuando el
 * bean se crea más de una vez en la misma JVM.
 */
class ConfiguracionFirebaseTest {

    private static final String MENSAJE_CREDENCIAL_INVALIDA = "La credencial de Firebase no es válida.";
    private static final String MENSAJE_OTRO_PROYECTO = "La credencial de Firebase es de otro proyecto.";
    private static final String ID_PROYECTO = "mejor-vejez-pruebas";

    @AfterEach
    void limpiarAppFirebase() {
        for (FirebaseApp app : FirebaseApp.getApps()) {
            if (ConfiguracionFirebase.NOMBRE_APP_FIREBASE.equals(app.getName())) {
                app.delete();
            }
        }
    }

    @Test
    void base64InvalidoLanzaCredencialInvalida() {
        String base64Invalido = "esto no es base64 válido!!!";

        assertThatThrownBy(() -> new ConfiguracionFirebase(base64Invalido, ID_PROYECTO).proveedorIdentidad())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MENSAJE_CREDENCIAL_INVALIDA)
                .hasNoCause()
                .satisfies(excepcion -> assertCadenaDeCausasSinCredencial(excepcion, base64Invalido));
    }

    @Test
    void jsonInvalidoLanzaCredencialInvalida() {
        String base64DeTextoQueNoEsJson = Base64.getEncoder()
                .encodeToString("esto no es un json de credenciales".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new ConfiguracionFirebase(base64DeTextoQueNoEsJson, ID_PROYECTO).proveedorIdentidad())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MENSAJE_CREDENCIAL_INVALIDA)
                .hasNoCause()
                .satisfies(excepcion -> assertCadenaDeCausasSinCredencial(excepcion, base64DeTextoQueNoEsJson));
    }

    @Test
    void credencialValidaReutilizaElMismoAppFirebaseAlCrearElBeanDosVeces() throws Exception {
        String base64Credencial = credencialDeCuentaDeServicioSintetica();

        ProveedorIdentidad primero = new ConfiguracionFirebase(base64Credencial, ID_PROYECTO).proveedorIdentidad();
        ProveedorIdentidad segundo = new ConfiguracionFirebase(base64Credencial, ID_PROYECTO).proveedorIdentidad();

        assertThat(primero).isInstanceOf(ProveedorIdentidadFirebase.class);
        assertThat(segundo).isInstanceOf(ProveedorIdentidadFirebase.class);
    }

    @Test
    void credencialDeOtroProyectoNoArranca() throws Exception {
        String base64Credencial = credencialDeCuentaDeServicioSintetica();

        assertThatThrownBy(() -> new ConfiguracionFirebase(base64Credencial, "otro-proyecto").proveedorIdentidad())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MENSAJE_OTRO_PROYECTO)
                .hasNoCause();
        assertThat(FirebaseApp.getApps())
                .noneMatch(app -> ConfiguracionFirebase.NOMBRE_APP_FIREBASE.equals(app.getName()));
    }

    @Test
    void credencialDeOtroProyectoNoArrancaAunqueElAppYaExista() throws Exception {
        String base64Credencial = credencialDeCuentaDeServicioSintetica();
        new ConfiguracionFirebase(base64Credencial, ID_PROYECTO).proveedorIdentidad();

        assertThatThrownBy(() -> new ConfiguracionFirebase(base64Credencial, "otro-proyecto").proveedorIdentidad())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MENSAJE_OTRO_PROYECTO)
                .hasNoCause();
    }

    private void assertCadenaDeCausasSinCredencial(Throwable excepcion, String credencial) {
        Throwable actual = excepcion;
        while (actual != null) {
            assertThat(actual.getMessage()).doesNotContain(credencial);
            actual = actual.getCause();
        }
    }

    /** Un JSON de cuenta de servicio sintético (llave RSA generada en la prueba, sin relación con ninguna cuenta real), en base64. */
    private String credencialDeCuentaDeServicioSintetica() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        KeyPair parDeLlaves = generador.generateKeyPair();

        String llavePrivadaPem = aPemPkcs8(parDeLlaves.getPrivate().getEncoded());
        String json = """
                {
                  "type": "service_account",
                  "project_id": "%s",
                  "private_key_id": "clave-prueba-1",
                  "private_key": "%s",
                  "client_email": "cuentas-prueba@mejor-vejez-pruebas.iam.gserviceaccount.com",
                  "client_id": "123456789012345678901",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(ID_PROYECTO, llavePrivadaPem.replace("\n", "\\n"));

        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String aPemPkcs8(byte[] llavePrivadaDer) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(llavePrivadaDer);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }
}
