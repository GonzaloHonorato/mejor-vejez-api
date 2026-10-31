package cl.adndigital.mejorvejez.cuentas;

import java.util.Optional;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionServicioExterno;

/**
 * Implementación real de {@link ProveedorIdentidad} sobre el SDK de
 * administración de Firebase. Nunca registra en el log la contraseña
 * temporal ni el enlace de restablecimiento; de un error de Firebase solo
 * registra su código, nunca el mensaje crudo que pueda traer el correo o
 * los datos de la cuenta.
 */
public class ProveedorIdentidadFirebase implements ProveedorIdentidad {

    private static final Logger LOG = LoggerFactory.getLogger(ProveedorIdentidadFirebase.class);
    private static final String MENSAJE_SERVICIO_NO_DISPONIBLE = "El servicio de identidad no está disponible.";

    private final FirebaseAuth firebaseAuth;

    public ProveedorIdentidadFirebase(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public Optional<CuentaIdentidad> buscarPorCorreo(String email) {
        try {
            UserRecord usuario = firebaseAuth.getUserByEmail(email);
            return Optional.of(aCuentaIdentidad(usuario));
        } catch (FirebaseAuthException excepcion) {
            if (excepcion.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                return Optional.empty();
            }
            throw errorInesperado(excepcion);
        }
    }

    @Override
    public CuentaIdentidad crear(String email, String nombre, String clave) {
        try {
            UserRecord.CreateRequest solicitud = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setDisplayName(nombre)
                    .setPassword(clave)
                    .setEmailVerified(true)
                    .setDisabled(false);
            return aCuentaIdentidad(firebaseAuth.createUser(solicitud));
        } catch (FirebaseAuthException excepcion) {
            if (excepcion.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                throw new ExcepcionConflicto("Ya existe una cuenta con ese correo");
            }
            throw errorInesperado(excepcion);
        }
    }

    @Override
    public void fijarClave(String uid, String clave) {
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setPassword(clave));
        } catch (FirebaseAuthException excepcion) {
            throw errorInesperado(excepcion);
        }
    }

    @Override
    public void eliminar(String uid) {
        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException excepcion) {
            throw errorInesperado(excepcion);
        }
    }

    @Override
    public String enlaceRestablecerClave(String email) {
        try {
            return firebaseAuth.generatePasswordResetLink(email);
        } catch (FirebaseAuthException excepcion) {
            throw errorInesperado(excepcion);
        }
    }

    private CuentaIdentidad aCuentaIdentidad(UserRecord usuario) {
        return new CuentaIdentidad(usuario.getUid(), usuario.getEmail());
    }

    /** Traduce cualquier otro error de Firebase a un 503 sin exponer su detalle. */
    private ExcepcionServicioExterno errorInesperado(FirebaseAuthException excepcion) {
        LOG.error("Error del proveedor de identidad: {}", excepcion.getAuthErrorCode());
        return new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, MENSAJE_SERVICIO_NO_DISPONIBLE);
    }
}
