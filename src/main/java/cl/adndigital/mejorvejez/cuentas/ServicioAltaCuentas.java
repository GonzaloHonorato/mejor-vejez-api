package cl.adndigital.mejorvejez.cuentas;

import java.util.Locale;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Orquestador del alta de cuentas (HU-01): crea la cuenta en el proveedor
 * de identidad (Firebase) sin transacción, guarda el usuario en {@code
 * USUARIO} en una transacción propia (que también ejecuta la acción
 * adicional del llamador, p. ej. incorporarlo a un grupo) y, si esa
 * transacción falla, compensa borrando la cuenta recién creada en el
 * proveedor antes de relanzar, para no dejar una cuenta huérfana. Al
 * terminar, pide a Alertas el correo de cuenta nueva (si corresponde) sin
 * que una falla de Alertas afecte el resultado: la cuenta queda creada y la
 * contraseña se devuelve igual.
 *
 * <p>No es {@code @Transactional}: la llamada al proveedor de identidad no
 * debe quedar atada a ninguna transacción de base de datos.
 *
 * <p>{@link #crearCuenta} y {@link #reenviarAcceso} son atajos para la ruta
 * del administrador: exigen el rol de administrador ellos mismos y fijan
 * ellos el actor de la auditoría. {@link #crear} es el método
 * general, sin autorización propia, para que cada ruta (administrador,
 * coordinador de un grupo, registro propio) aplique la suya y resuelva su
 * propio {@code idActor} antes de llamarlo.
 */
@Service
public class ServicioAltaCuentas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioAltaCuentas.class);

    private static final String MODULO = "cuentas";
    private static final String TIPO_ENTIDAD = "USUARIO";
    private static final String ACCION_REENVIAR_ACCESO = "REENVIAR_ACCESO";
    private static final String MENSAJE_CORREO_EXISTENTE = "Ya existe una cuenta con ese correo";
    private static final String MENSAJE_USUARIO_NO_EXISTE = "El usuario no existe";
    private static final String MENSAJE_CUENTA_INACTIVA = "La cuenta está inactiva";
    private static final String TABLA_ESTADO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final ProveedorIdentidad proveedorIdentidad;
    private final RegistroCuentas registroCuentas;
    private final RepositorioUsuario repositorioUsuario;
    private final GeneradorClaveTemporal generadorClaveTemporal;
    private final ClienteServicios clienteServicios;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioAuditoria servicioAuditoria;
    private final ServicioCatalogos servicioCatalogos;
    private final TransactionTemplate transaccion;

    public ServicioAltaCuentas(ProveedorIdentidad proveedorIdentidad, RegistroCuentas registroCuentas,
            RepositorioUsuario repositorioUsuario, GeneradorClaveTemporal generadorClaveTemporal,
            ClienteServicios clienteServicios, ServicioAutorizacion servicioAutorizacion,
            ServicioAuditoria servicioAuditoria, ServicioCatalogos servicioCatalogos,
            PlatformTransactionManager gestorTransacciones) {
        this.proveedorIdentidad = proveedorIdentidad;
        this.registroCuentas = registroCuentas;
        this.repositorioUsuario = repositorioUsuario;
        this.generadorClaveTemporal = generadorClaveTemporal;
        this.clienteServicios = clienteServicios;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioAuditoria = servicioAuditoria;
        this.servicioCatalogos = servicioCatalogos;
        this.transaccion = new TransactionTemplate(gestorTransacciones);
        this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Da de alta la cuenta descrita en {@code alta}: normaliza y valida el
     * correo, lo crea en el proveedor de identidad, guarda el usuario (y la
     * acción adicional de {@code alta}) en una transacción y, si
     * corresponde, pide a Alertas el correo de cuenta nueva. Si {@code
     * alta} trae una contraseña elegida por la persona, el resultado no la
     * devuelve ({@link CuentaCreada#claveTemporal()} es nulo): ya la conoce
     * y no debe volver a viajar en ninguna respuesta.
     *
     * @throws ExcepcionConflicto el correo ya existe en {@code USUARIO} o en
     *                            el proveedor de identidad.
     */
    public CuentaCreada crear(AltaCuenta alta) {
        String email = normalizarCorreo(alta.email());
        String nombre = alta.nombre().trim();
        validarCorreoLibre(email);

        String clave = alta.claveElegida() != null ? alta.claveElegida() : generadorClaveTemporal.generar();
        CuentaIdentidad cuentaIdentidad = proveedorIdentidad.crear(email, nombre, clave);

        Usuario usuario = registrarConCompensacion(cuentaIdentidad, email, nombre, alta.telefono(), alta.origen(),
                alta.idActor(), alta.enTransaccion());

        boolean correoEnviado = alta.enviarCorreo()
                && clienteServicios.enviarCorreoCuentaNueva(nombre, email, clave);
        String claveTemporal = alta.claveElegida() != null ? null : clave;
        return new CuentaCreada(usuario, claveTemporal, correoEnviado);
    }

    /**
     * Atajo sobre {@link #crear} para el alta por el administrador ({@code
     * POST /api/usuarios}): sin acción adicional, con correo de cuenta
     * nueva, contraseña temporal generada y {@code origen =
     * ADMINISTRADOR} fijo (esta ruta es siempre del administrador, no la
     * elige quien llama).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado quien llama no es administrador.
     */
    public CuentaCreada crearCuenta(String nombre, String email, String telefono) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        return crear(new AltaCuenta(nombre, email, telefono, OrigenCuenta.ADMINISTRADOR, administrador.getIdUsuario(),
                null, true, usuarioCreado -> { }));
    }

    /**
     * Genera una contraseña temporal nueva para {@code idUsuario}, la fija
     * en el proveedor de identidad y pide a Alertas que la reenvíe por
     * correo ({@code POST /api/usuarios/{id}/reenviar-acceso}).
     *
     * <p>La auditoría y el cambio de contraseña en el proveedor van en una
     * misma transacción, auditando PRIMERO: si la auditoría falla, la
     * contraseña nunca se cambia; si falla el proveedor, la transacción
     * vuelve atrás y no queda un evento de un reenvío que no ocurrió. El
     * correo se pide después de confirmar la transacción.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado quien llama no es administrador.
     * @throws ExcepcionNoEncontrado el usuario no existe.
     * @throws ExcepcionReglaNegocio la cuenta no está ACTIVA.
     */
    public CuentaCreada reenviarAcceso(long idUsuario) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        Usuario usuario = repositorioUsuario.findById(idUsuario)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_USUARIO_NO_EXISTE));
        exigirActivo(usuario);

        String claveNueva = generadorClaveTemporal.generar();
        transaccion.executeWithoutResult(estado -> {
            servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                    String.valueOf(usuario.getIdUsuario()), null, administrador.getIdUsuario(), ACCION_REENVIAR_ACCESO,
                    ResultadoAuditoria.EXITO, null));
            proveedorIdentidad.fijarClave(usuario.getUidFirebase(), claveNueva);
        });

        boolean correoEnviado = clienteServicios.enviarCorreoCuentaNueva(usuario.getNombre(), usuario.getEmail(),
                claveNueva);
        return new CuentaCreada(usuario, claveNueva, correoEnviado);
    }

    /**
     * Guarda el usuario (y ejecuta {@code enTransaccion}) en una
     * transacción propia; si lanza, borra la cuenta recién creada en el
     * proveedor de identidad y relanza la misma excepción, para no dejar
     * una cuenta huérfana en Firebase. Si la propia compensación
     * (eliminar en el proveedor) también falla, no se pierde el error
     * original: queda como {@linkplain Throwable#addSuppressed(Throwable)
     * suprimido} y se deja un log con el {@code uid} (nunca la
     * contraseña) para borrar la cuenta huérfana a mano.
     */
    private Usuario registrarConCompensacion(CuentaIdentidad cuentaIdentidad, String email, String nombre,
            String telefono, OrigenCuenta origen, Long idActor, Consumer<Usuario> enTransaccion) {
        try {
            return transaccion.execute(estado ->
                    registroCuentas.registrar(cuentaIdentidad.uid(), email, nombre, telefono, origen, idActor,
                            enTransaccion));
        } catch (RuntimeException excepcionOriginal) {
            try {
                proveedorIdentidad.eliminar(cuentaIdentidad.uid());
            } catch (RuntimeException excepcionAlCompensar) {
                LOG.error("No se pudo borrar en el proveedor de identidad la cuenta del uid {} tras una falla al "
                        + "guardarla; queda huérfana en el proveedor y debe borrarse a mano.", cuentaIdentidad.uid());
                excepcionOriginal.addSuppressed(excepcionAlCompensar);
            }
            throw excepcionOriginal;
        }
    }

    private void validarCorreoLibre(String email) {
        boolean existeEnCore = repositorioUsuario.findByEmail(email).isPresent();
        boolean existeEnProveedor = !existeEnCore && proveedorIdentidad.buscarPorCorreo(email).isPresent();
        if (existeEnCore || existeEnProveedor) {
            throw new ExcepcionConflicto(MENSAJE_CORREO_EXISTENTE);
        }
    }

    private void exigirActivo(Usuario usuario) {
        long idActivo = servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, ESTADO_ACTIVO);
        if (usuario.getIdEstado() != idActivo) {
            throw new ExcepcionReglaNegocio(MENSAJE_CUENTA_INACTIVA);
        }
    }

    /** Correo sin espacios en los extremos y en minúsculas (mismo criterio que {@code ServicioUsuarioActual}). */
    private String normalizarCorreo(String correo) {
        return correo.trim().toLowerCase(Locale.ROOT);
    }
}
