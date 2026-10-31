package cl.adndigital.mejorvejez.cuentas;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige la implementación de {@link ProveedorIdentidad} según haya o no una
 * credencial de Firebase configurada ({@code
 * mejorvejez.firebase.credenciales-base64}): con ella, inicializa un {@link
 * FirebaseApp} propio (con nombre propio, para no interferir con otra
 * inicialización de Firebase en la misma JVM) a partir del JSON de la cuenta
 * de servicio decodificado; sin ella, {@link ProveedorIdentidadNoConfigurado}.
 *
 * <p>Si la credencial es de un proyecto distinto del configurado en {@code
 * app.firebase.project-id} (el mismo que valida los ID token), la
 * aplicación no arranca.
 *
 * <p>Nunca registra en el log el contenido de la credencial, decodificada o
 * no.
 */
@Configuration
public class ConfiguracionFirebase {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionFirebase.class);

    /** Paquete (no privado) para que las pruebas puedan limpiar el {@link FirebaseApp} que queda registrado en la JVM. */
    static final String NOMBRE_APP_FIREBASE = "mejor-vejez-identidad";

    private final String credencialesBase64;
    private final String idProyecto;

    /**
     * @param idProyecto id del proyecto de Firebase que valida los ID token
     *                   ({@code app.firebase.project-id}); la credencial
     *                   tiene que ser de ese mismo proyecto.
     */
    public ConfiguracionFirebase(@Value("${mejorvejez.firebase.credenciales-base64:}") String credencialesBase64,
            @Value("${app.firebase.project-id:}") String idProyecto) {
        this.credencialesBase64 = credencialesBase64;
        this.idProyecto = idProyecto;
    }

    @Bean
    public ProveedorIdentidad proveedorIdentidad() {
        if (credencialesBase64 == null || credencialesBase64.isBlank()) {
            return new ProveedorIdentidadNoConfigurado();
        }
        GoogleCredentials credenciales = leerCredenciales();
        exigirMismoProyecto(credenciales);
        return new ProveedorIdentidadFirebase(FirebaseAuth.getInstance(appFirebase(credenciales)));
    }

    private GoogleCredentials leerCredenciales() {
        try (InputStream credenciales = new ByteArrayInputStream(Base64.getDecoder().decode(credencialesBase64))) {
            return GoogleCredentials.fromStream(credenciales);
        } catch (IOException | IllegalArgumentException excepcion) {
            // No se registra la causa: puede traer bytes de la credencial.
            LOG.error("No se pudo inicializar Firebase: la credencial configurada no es válida.");
            throw new IllegalStateException("La credencial de Firebase no es válida.");
        }
    }

    /**
     * Exige que el {@code project_id} de la credencial (cuenta de servicio)
     * sea el proyecto configurado: si no, las cuentas se crearían en un
     * proyecto distinto del que valida los ID token, y nadie podría
     * ingresar con ellas. Una credencial que no es de cuenta de servicio no
     * trae {@code project_id} y tampoco se acepta. Ni el log ni la excepción
     * incluyen los ids de proyecto ni nada de la credencial.
     */
    private void exigirMismoProyecto(GoogleCredentials credenciales) {
        String proyectoDeLaCredencial = credenciales instanceof ServiceAccountCredentials cuentaDeServicio
                ? cuentaDeServicio.getProjectId()
                : null;
        if (proyectoDeLaCredencial == null || !proyectoDeLaCredencial.equals(idProyecto)) {
            LOG.error("No se pudo inicializar Firebase: la credencial configurada es de otro proyecto.");
            throw new IllegalStateException("La credencial de Firebase es de otro proyecto.");
        }
    }

    private FirebaseApp appFirebase(GoogleCredentials credenciales) {
        for (FirebaseApp app : FirebaseApp.getApps()) {
            if (NOMBRE_APP_FIREBASE.equals(app.getName())) {
                return app;
            }
        }
        FirebaseOptions opciones = FirebaseOptions.builder()
                .setCredentials(credenciales)
                .build();
        return FirebaseApp.initializeApp(opciones, NOMBRE_APP_FIREBASE);
    }
}
