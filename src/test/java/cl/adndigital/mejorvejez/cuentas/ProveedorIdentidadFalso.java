package cl.adndigital.mejorvejez.cuentas;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;

/**
 * Implementación de {@link ProveedorIdentidad} en memoria para las pruebas
 * del core: nunca llama a Firebase real. Guarda las cuentas creadas y
 * permite forzar que cualquier operación lance una excepción, o que un
 * correo ya exista en el proveedor sin haber pasado por {@link #crear}
 * (para probar validaciones de duplicado contra el proveedor). También
 * guarda la última contraseña fijada por {@code uid} ({@link #crear} y
 * {@link #fijarClave}), para que las pruebas de reenvío de acceso
 * verifiquen que la contraseña efectivamente cambió, y el {@code oobCode}
 * de la última llamada a {@link #enlaceRestablecerClave} ({@link
 * #ultimoOobCodeGenerado}), para que las pruebas de recuperación de
 * contraseña verifiquen el enlace armado sin depender de un valor fijo.
 *
 * <p>Los uids generados usan {@link UUID#randomUUID()}, no un contador:
 * esta clase es el {@code @Primary} de {@code ConfiguracionTestcontainers},
 * así que CADA contexto de prueba que la importe crea su PROPIA instancia
 * (un contador propio, reiniciado en cero), pero todos esos contextos
 * comparten el mismo Oracle de Testcontainers; si dos contextos distintos
 * generaran el mismo "uid-falso-1" para dos usuarios guardados, chocarían
 * con {@code UQ_USUARIO_UID_FIREBASE}. Un {@code UUID} por llamada es único
 * sin necesitar coordinación entre instancias ni con lo ya persistido.
 */
public class ProveedorIdentidadFalso implements ProveedorIdentidad {

    private final Map<String, CuentaIdentidad> cuentasPorCorreo = new ConcurrentHashMap<>();
    private final Map<String, String> claveActualPorUid = new ConcurrentHashMap<>();

    private RuntimeException errorAlBuscar;
    private RuntimeException errorAlCrear;
    private RuntimeException errorAlFijarClave;
    private RuntimeException errorAlEliminar;
    private RuntimeException errorAlGenerarEnlace;
    private volatile String ultimoOobCodeGenerado;
    private volatile String enlacePersonalizado;

    @Override
    public Optional<CuentaIdentidad> buscarPorCorreo(String email) {
        if (errorAlBuscar != null) {
            throw errorAlBuscar;
        }
        return Optional.ofNullable(cuentasPorCorreo.get(email));
    }

    @Override
    public CuentaIdentidad crear(String email, String nombre, String clave) {
        if (errorAlCrear != null) {
            throw errorAlCrear;
        }
        if (cuentasPorCorreo.containsKey(email)) {
            throw new ExcepcionConflicto("Ya existe una cuenta con ese correo");
        }
        CuentaIdentidad cuenta = new CuentaIdentidad(uidFalsoUnico(), email);
        cuentasPorCorreo.put(email, cuenta);
        claveActualPorUid.put(cuenta.uid(), clave);
        return cuenta;
    }

    @Override
    public void fijarClave(String uid, String clave) {
        if (errorAlFijarClave != null) {
            throw errorAlFijarClave;
        }
        claveActualPorUid.put(uid, clave);
    }

    /** Última contraseña fijada para {@code uid} (por {@link #crear} o {@link #fijarClave}); {@code null} si ninguna. */
    public String claveActualDe(String uid) {
        return claveActualPorUid.get(uid);
    }

    @Override
    public void eliminar(String uid) {
        if (errorAlEliminar != null) {
            throw errorAlEliminar;
        }
        cuentasPorCorreo.values().removeIf(cuenta -> cuenta.uid().equals(uid));
        claveActualPorUid.remove(uid);
    }

    @Override
    public String enlaceRestablecerClave(String email) {
        if (errorAlGenerarEnlace != null) {
            throw errorAlGenerarEnlace;
        }
        if (enlacePersonalizado != null) {
            return enlacePersonalizado;
        }
        ultimoOobCodeGenerado = UUID.randomUUID().toString();
        return "https://proveedor-falso.test/restablecer?oobCode=" + ultimoOobCodeGenerado;
    }

    /** El {@code oobCode} generado por la última llamada a {@link #enlaceRestablecerClave}; {@code null} si ninguna. */
    public String ultimoOobCodeGenerado() {
        return ultimoOobCodeGenerado;
    }

    /**
     * Fuerza que {@link #enlaceRestablecerClave} devuelva exactamente
     * {@code enlace} (en vez del enlace con {@code oobCode} aleatorio), para
     * probar casos puntuales del formato de la URL de Firebase (parámetros
     * en otro orden, sin {@code oobCode}, codificados de otra forma).
     */
    public void forzarEnlace(String enlace) {
        this.enlacePersonalizado = enlace;
    }

    /** Registra una cuenta ya existente en el proveedor, sin pasar por {@link #crear}. */
    public CuentaIdentidad agregarCuentaExistente(String email) {
        CuentaIdentidad cuenta = new CuentaIdentidad(uidFalsoUnico(), email);
        cuentasPorCorreo.put(email, cuenta);
        return cuenta;
    }

    public void forzarErrorAlBuscar(RuntimeException error) {
        this.errorAlBuscar = error;
    }

    public void forzarErrorAlCrear(RuntimeException error) {
        this.errorAlCrear = error;
    }

    public void forzarErrorAlFijarClave(RuntimeException error) {
        this.errorAlFijarClave = error;
    }

    public void forzarErrorAlEliminar(RuntimeException error) {
        this.errorAlEliminar = error;
    }

    public void forzarErrorAlGenerarEnlace(RuntimeException error) {
        this.errorAlGenerarEnlace = error;
    }

    /** Vuelve al estado inicial: sin cuentas y sin errores forzados. */
    public void limpiar() {
        cuentasPorCorreo.clear();
        claveActualPorUid.clear();
        errorAlBuscar = null;
        errorAlCrear = null;
        errorAlFijarClave = null;
        errorAlEliminar = null;
        errorAlGenerarEnlace = null;
        ultimoOobCodeGenerado = null;
        enlacePersonalizado = null;
    }

    private String uidFalsoUnico() {
        return "uid-falso-" + UUID.randomUUID();
    }
}
