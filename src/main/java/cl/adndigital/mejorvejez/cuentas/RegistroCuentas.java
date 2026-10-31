package cl.adndigital.mejorvejez.cuentas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Parte transaccional del alta de una cuenta: guarda el {@link Usuario}
 * cuya cuenta ya se creó en el proveedor de identidad, audita {@code
 * CREAR_CUENTA} y ejecuta la acción adicional del llamador (p. ej.
 * incorporarlo a un grupo) dentro de la misma transacción. {@link
 * ServicioAltaCuentas} la invoca envuelta en su propio {@code
 * TransactionTemplate}: si alguno de estos pasos lanza, deja a {@code
 * ServicioAltaCuentas} la compensación de borrar la cuenta en el proveedor
 * de identidad.
 */
@Component
class RegistroCuentas {

    static final String MODULO = "cuentas";
    static final String TIPO_ENTIDAD = "USUARIO";
    static final String ACCION_CREAR_CUENTA = "CREAR_CUENTA";

    private static final String TABLA_ESTADO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final RepositorioUsuario repositorioUsuario;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    RegistroCuentas(RepositorioUsuario repositorioUsuario, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Guarda el usuario nuevo (ACTIVO, no administrador), audita {@code
     * CREAR_CUENTA} con {@code origen} y el actor (nunca con la
     * contraseña) y ejecuta {@code enTransaccion} sobre el usuario ya
     * guardado.
     *
     * @param idActor usuario que ejecuta el alta; si viene {@code
     *                null} y {@code origen} es {@link OrigenCuenta#REGISTRO},
     *                el actor es el propio usuario recién guardado (se
     *                registró a sí mismo).
     */
    Usuario registrar(String uidFirebase, String email, String nombre, String telefono, OrigenCuenta origen,
            Long idActor, Consumer<Usuario> enTransaccion) {
        long idActivo = servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, ESTADO_ACTIVO);
        Usuario usuario = repositorioUsuario.saveAndFlush(
                new Usuario(uidFirebase, email, nombre, telefono, idActivo, OffsetDateTime.now(reloj)));

        Long actor = (idActor == null && origen == OrigenCuenta.REGISTRO) ? usuario.getIdUsuario() : idActor;
        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(usuario.getIdUsuario()),
                null, actor, ACCION_CREAR_CUENTA, ResultadoAuditoria.EXITO, Map.of("origen", origen.name())));

        enTransaccion.accept(usuario);
        return usuario;
    }
}
