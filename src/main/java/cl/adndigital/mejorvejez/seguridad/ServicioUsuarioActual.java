package cl.adndigital.mejorvejez.seguridad;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoAutenticado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Resuelve el usuario autenticado (ID token de Firebase ya validado como
 * {@link Jwt} por Spring Security) contra {@code USUARIO}, y lo da de alta
 * en su primer ingreso. Todo servicio que necesite al usuario actual debe
 * pedirlo con {@link #obtener()}.
 *
 * <p><b>Transacciones.</b> {@code obtener()} no es {@code @Transactional}:
 * el alta corre en una transacción propia ({@code REQUIRES_NEW}, con
 * {@link TransactionTemplate}) que inserta el usuario y su evento de
 * auditoría juntos. Si dos primeras peticiones del mismo uid compiten, la
 * segunda choca con {@code UQ_USUARIO_UID_FIREBASE} y recibe una {@link
 * DataIntegrityViolationException}; esa transacción queda marcada para
 * rollback y ya no sirve para leer. Como el alta corrió aparte, la
 * relectura por uid ocurre fuera de ella (en la transacción del llamador si
 * la hay, o en una nueva del repositorio) y ve la fila que confirmó la
 * petición ganadora. Por lo mismo, un fallo del alta no contamina la
 * transacción de quien llame a {@code obtener()}.
 *
 * <p>El correo se normaliza ({@code trim} + minúsculas) antes de validarlo,
 * buscarlo y guardarlo, para que mayúsculas o espacios no permitan dos
 * cuentas con el mismo correo.
 */
@Service
public class ServicioUsuarioActual {

    private static final String ACCION_PRIMER_INGRESO = "CREAR_USUARIO_PRIMER_INGRESO";

    private static final String MODULO = "usuarios";
    private static final String TABLA_ESTADO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final int LARGO_MAXIMO_NOMBRE = 160;
    private static final String MENSAJE_CORREO_DE_OTRA_CUENTA = "El correo ya está asociado a otra cuenta";
    private static final String MENSAJE_NO_AUTENTICADO = "Se requiere una sesión válida.";

    private final RepositorioUsuario repositorio;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;
    private final TransactionTemplate transaccionNueva;

    public ServicioUsuarioActual(RepositorioUsuario repositorio, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj, PlatformTransactionManager gestorTransacciones) {
        this.repositorio = repositorio;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
        this.transaccionNueva = new TransactionTemplate(gestorTransacciones);
        this.transaccionNueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Usuario de la petición en curso; lo crea si es su primer ingreso.
     *
     * @throws ExcepcionReglaNegocio   primer ingreso de una cuenta sin correo.
     * @throws ExcepcionConflicto      el correo ya pertenece a otro uid.
     * @throws ExcepcionAccesoDenegado el usuario no está ACTIVO.
     * @throws ExcepcionNoAutenticado  no hay un JWT con {@code sub} en el contexto.
     */
    public Usuario obtener() {
        Jwt jwt = jwtActual();
        String uid = jwt.getSubject();
        Usuario usuario = repositorio.findByUidFirebase(uid).orElseGet(() -> darDeAlta(jwt));
        validarHabilitado(usuario);
        return usuario;
    }

    private Usuario darDeAlta(Jwt jwt) {
        String uid = jwt.getSubject();
        String email = normalizarCorreo(jwt.getClaimAsString("email"));
        if (email.isEmpty()) {
            throw new ExcepcionReglaNegocio("La cuenta no tiene un correo asociado");
        }
        if (repositorio.findByEmail(email).isPresent()) {
            throw new ExcepcionConflicto(MENSAJE_CORREO_DE_OTRA_CUENTA);
        }
        try {
            return transaccionNueva.execute(estado -> crear(uid, email, nombreDe(jwt, email)));
        } catch (DataIntegrityViolationException violacion) {
            return resolverCarrera(uid, email, violacion);
        }
    }

    /**
     * Tras una violación de integridad al crear: si otra petición concurrente
     * ya dio de alta este uid, se devuelve ese usuario; si dio de alta el
     * mismo correo con otro uid, es el conflicto de correo. Cualquier otra
     * violación (largo de columna, NOT NULL...) no es una carrera y se
     * relanza tal cual. Corre fuera de la transacción fallida del alta.
     */
    private Usuario resolverCarrera(String uid, String email, DataIntegrityViolationException violacion) {
        Optional<Usuario> mismoUid = repositorio.findByUidFirebase(uid);
        if (mismoUid.isPresent()) {
            return mismoUid.get();
        }
        boolean correoTomadoPorOtroUid = repositorio.findByEmail(email)
                .filter(otro -> !uid.equals(otro.getUidFirebase()))
                .isPresent();
        if (correoTomadoPorOtroUid) {
            throw new ExcepcionConflicto(MENSAJE_CORREO_DE_OTRA_CUENTA);
        }
        throw violacion;
    }

    private Usuario crear(String uid, String email, String nombre) {
        long idActivo = servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, ESTADO_ACTIVO);
        Usuario usuario = repositorio.saveAndFlush(
                new Usuario(uid, email, nombre, idActivo, OffsetDateTime.now(reloj)));
        servicioAuditoria.registrar(new EventoARegistrar(MODULO, "USUARIO",
                String.valueOf(usuario.getIdUsuario()), null, usuario.getIdUsuario(),
                ACCION_PRIMER_INGRESO, ResultadoAuditoria.EXITO, Map.of("uidFirebase", uid)));
        return usuario;
    }

    private void validarHabilitado(Usuario usuario) {
        long idActivo = servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, ESTADO_ACTIVO);
        if (usuario.getIdEstado() != idActivo) {
            throw new ExcepcionAccesoDenegado("La cuenta está deshabilitada");
        }
    }

    /** Correo sin espacios en los extremos y en minúsculas; vacío si no viene. */
    private String normalizarCorreo(String correo) {
        return correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Claim {@code name} (o, si falta, la parte local del correo) sin
     * espacios en los extremos y truncado a {@value #LARGO_MAXIMO_NOMBRE}
     * code points y a {@value #LARGO_MAXIMO_NOMBRE} bytes en UTF-8: la columna
     * {@code NOMBRE} es {@code VARCHAR2(160)} con semántica de bytes, así que
     * un nombre con tildes puede exceder la columna con menos de 160
     * caracteres.
     */
    private String nombreDe(Jwt jwt, String email) {
        String nombre = jwt.getClaimAsString("name");
        nombre = nombre == null ? "" : nombre.trim();
        if (nombre.isEmpty()) {
            int arroba = email.indexOf('@');
            nombre = arroba > 0 ? email.substring(0, arroba) : email;
        }
        if (nombre.codePointCount(0, nombre.length()) > LARGO_MAXIMO_NOMBRE) {
            nombre = nombre.substring(0, nombre.offsetByCodePoints(0, LARGO_MAXIMO_NOMBRE)).trim();
        }
        return truncarABytes(nombre, LARGO_MAXIMO_NOMBRE);
    }

    /**
     * Recorta {@code texto} para que ocupe como máximo {@code maximoBytes} en
     * UTF-8, quitando code points completos desde el final (nunca corta un
     * carácter a la mitad).
     */
    private String truncarABytes(String texto, int maximoBytes) {
        if (texto.getBytes(StandardCharsets.UTF_8).length <= maximoBytes) {
            return texto;
        }
        int bytes = 0;
        int fin = 0;
        while (fin < texto.length()) {
            int codePoint = texto.codePointAt(fin);
            int bytesCodePoint = String.valueOf(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes + bytesCodePoint > maximoBytes) {
                break;
            }
            bytes += bytesCodePoint;
            fin += Character.charCount(codePoint);
        }
        return texto.substring(0, fin).trim();
    }

    private Jwt jwtActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof Jwt jwt)
                || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ExcepcionNoAutenticado(MENSAJE_NO_AUTENTICADO);
        }
        return jwt;
    }
}
