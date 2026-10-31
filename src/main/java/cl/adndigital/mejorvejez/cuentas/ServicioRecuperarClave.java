package cl.adndigital.mejorvejez.cuentas;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionDemasiadosIntentos;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Atiende {@code POST /api/publico/recuperar-clave} (ruta pública, sin
 * token): responde siempre 202, exista o no una cuenta con ese correo y
 * esté o no ACTIVA, para no dejar ver desde afuera qué correos tienen
 * cuenta. Solo pide a Alertas el correo de recuperación cuando el correo
 * normalizado corresponde a una cuenta ACTIVA.
 *
 * <p>El 202 se decide con solo normalizar el correo, pasar el límite de
 * intentos y auditar: la parte lenta (generar el enlace en Firebase y pedir
 * a Alertas que lo envíe) corre fuera del pedido en {@link
 * #ejecutorEnvioRecuperacion}, un executor acotado (ver {@code
 * ConfiguracionRecuperarClave}). Si la cola de ese executor está llena, el
 * envío se descarta con un log fijo, sin que la respuesta cambie.
 *
 * <p>El enlace se arma con el {@code oobCode} que trae el enlace de
 * restablecimiento que genera Firebase ({@link
 * ProveedorIdentidad#enlaceRestablecerClave}): nunca se guarda ni se pasa a
 * {@link ServicioAuditoria} ni a ningún log, solo viaja (re-codificado con
 * {@link URLEncoder}, porque ya viene decodificado) al correo que envía
 * Alertas. El código va en el fragmento del enlace ({@code
 * /restablecer-clave#codigo=...}), no en la consulta: el navegador nunca
 * envía el fragmento al servidor, así que no queda en los logs de acceso de
 * los proxies ni del servidor web. Si el enlace de Firebase no trae {@code
 * oobCode}, no se envía correo. Cualquier otra falla al generarlo o
 * enviarlo (incluida la falta de credencial de Firebase) se absorbe con un
 * log sin datos, para que la respuesta siga siendo la misma.
 */
@Service
public class ServicioRecuperarClave {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioRecuperarClave.class);

    private static final String MODULO = "cuentas";
    private static final String TIPO_ENTIDAD = "USUARIO";
    private static final String ACCION_SOLICITAR_RECUPERAR_CLAVE = "SOLICITAR_RECUPERAR_CLAVE";
    private static final String TABLA_ESTADO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final String RUTA_RESTABLECER_CLAVE = "/restablecer-clave#codigo=";
    private static final String PARAMETRO_OOB_CODE = "oobCode";

    /** {@code ID_ENTIDAD} de la auditoría cuando el correo no corresponde a ninguna cuenta. */
    static final String ID_ENTIDAD_SIN_CUENTA = "DESCONOCIDO";

    private final ProveedorIdentidad proveedorIdentidad;
    private final RepositorioUsuario repositorioUsuario;
    private final ClienteServicios clienteServicios;
    private final ServicioAuditoria servicioAuditoria;
    private final ServicioCatalogos servicioCatalogos;
    private final LimitadorIntentos limitadorIntentos;
    private final EjecutorRecuperarClave ejecutorEnvioRecuperacion;
    private final String appUrl;

    /**
     * {@code ejecutorEnvioRecuperacion} se resuelve por tipo: hay un único
     * {@link EjecutorRecuperarClave} de producción ({@code
     * ConfiguracionRecuperarClave}), y las pruebas lo reemplazan con uno
     * síncrono marcado {@code @Primary} (sin que haga falta un {@code
     * @Qualifier}, porque el tipo ya es exclusivo de esta ruta).
     */
    public ServicioRecuperarClave(ProveedorIdentidad proveedorIdentidad, RepositorioUsuario repositorioUsuario,
            ClienteServicios clienteServicios, ServicioAuditoria servicioAuditoria,
            ServicioCatalogos servicioCatalogos, LimitadorIntentos limitadorIntentos,
            EjecutorRecuperarClave ejecutorEnvioRecuperacion, @Value("${mejorvejez.app-url}") String appUrl) {
        this.proveedorIdentidad = proveedorIdentidad;
        this.repositorioUsuario = repositorioUsuario;
        this.clienteServicios = clienteServicios;
        this.servicioAuditoria = servicioAuditoria;
        this.servicioCatalogos = servicioCatalogos;
        this.limitadorIntentos = limitadorIntentos;
        this.ejecutorEnvioRecuperacion = ejecutorEnvioRecuperacion;
        this.appUrl = appUrl;
    }

    /**
     * Procesa el pedido de recuperación de contraseña de {@code email}
     * desde {@code ip}. No devuelve nada: el llamador responde 202 siempre,
     * salvo que se exceda el límite de pedidos.
     *
     * @throws ExcepcionDemasiadosIntentos se superó el máximo de pedidos
     *                                     por correo o por IP en la ventana
     *                                     vigente.
     */
    public void solicitar(String email, String ip) {
        String correo = normalizarCorreo(email);
        if (!limitadorIntentos.permitirIntento(correo, ip)) {
            throw new ExcepcionDemasiadosIntentos();
        }

        Optional<Usuario> usuarioEncontrado = repositorioUsuario.findByEmail(correo);
        auditar(usuarioEncontrado.orElse(null));

        if (usuarioEncontrado.isEmpty() || !estaActivo(usuarioEncontrado.get())) {
            return;
        }
        encolarEnvioDeRecuperacion(usuarioEncontrado.get(), correo);
    }

    /**
     * Si la cuenta existe, {@code idEntidad} es el {@code idUsuario} y el
     * actor es ese mismo usuario; si no existe, {@code idEntidad} es {@value
     * #ID_ENTIDAD_SIN_CUENTA} (fijo, porque {@code ID_ENTIDAD} no admite
     * nulo) y el actor es nulo. El correo nunca queda en ninguna columna
     * del evento (ni como entidad ni en el detalle, que siempre es nulo):
     * no hay razón para dejar en la auditoría un correo de alguien que ni
     * siquiera tiene cuenta.
     */
    private void auditar(Usuario usuarioEncontrado) {
        String idEntidad = usuarioEncontrado != null ? String.valueOf(usuarioEncontrado.getIdUsuario())
                : ID_ENTIDAD_SIN_CUENTA;
        Long idUsuarioActor = usuarioEncontrado != null ? usuarioEncontrado.getIdUsuario() : null;
        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, idEntidad, null, idUsuarioActor,
                ACCION_SOLICITAR_RECUPERAR_CLAVE, ResultadoAuditoria.EXITO, null));
    }

    /**
     * Encola el envío en {@link #ejecutorEnvioRecuperacion}; si la cola
     * está llena, el {@code RejectedExecutionException} se absorbe con un
     * log fijo (nunca llega al llamador: el 202 ya se decidió).
     */
    private void encolarEnvioDeRecuperacion(Usuario usuario, String correo) {
        try {
            ejecutorEnvioRecuperacion.ejecutar(() -> enviarEnlaceDeRecuperacion(usuario, correo));
        } catch (RejectedExecutionException excepcion) {
            LOG.warn("No se pudo encolar el envío del enlace de recuperación de contraseña; la cola está llena.");
        }
    }

    private void enviarEnlaceDeRecuperacion(Usuario usuario, String correo) {
        try {
            String enlaceFirebase = proveedorIdentidad.enlaceRestablecerClave(correo);
            String codigo = extraerOobCode(enlaceFirebase);
            if (codigo.isEmpty()) {
                LOG.warn("El enlace de restablecimiento de Firebase no trajo oobCode; no se envía el correo.");
                return;
            }
            String enlace = appUrl + RUTA_RESTABLECER_CLAVE + URLEncoder.encode(codigo, StandardCharsets.UTF_8);
            clienteServicios.enviarCorreoRecuperarClave(usuario.getNombre(), correo, enlace);
        } catch (RuntimeException excepcion) {
            LOG.warn("No se pudo generar o enviar el enlace de recuperación de contraseña; "
                    + "la solicitud responde igual.");
        }
    }

    private boolean estaActivo(Usuario usuario) {
        long idActivo = servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, ESTADO_ACTIVO);
        return usuario.getIdEstado() == idActivo;
    }

    /** Correo sin espacios en los extremos y en minúsculas (mismo criterio que {@code ServicioAltaCuentas}). */
    private String normalizarCorreo(String correo) {
        return correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extrae el parámetro {@code oobCode} de la URL de consulta de {@code
     * enlaceFirebase} (en cualquier posición, con otros parámetros antes o
     * después), ya decodificado; vacío si no está.
     */
    private static String extraerOobCode(String enlaceFirebase) {
        String consulta = URI.create(enlaceFirebase).getRawQuery();
        if (consulta == null) {
            return "";
        }
        for (String parametro : consulta.split("&")) {
            int indiceIgual = parametro.indexOf('=');
            String clave = indiceIgual >= 0 ? parametro.substring(0, indiceIgual) : parametro;
            if (PARAMETRO_OOB_CODE.equals(clave)) {
                String valor = indiceIgual >= 0 ? parametro.substring(indiceIgual + 1) : "";
                return URLDecoder.decode(valor, StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
