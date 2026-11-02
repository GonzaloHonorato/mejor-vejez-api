package cl.adndigital.mejorvejez.usuarios;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionCampoInvalido;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.TelefonoChileno;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Administración global de cuentas: listar, ver, editar y cambiar el
 * estado de cualquier usuario. Cada operación exige administrador
 * primero, con {@link ServicioAutorizacion#exigirAdministrador()} (mismo
 * criterio documentado en {@code ServicioAutorizacion}: todo servicio que
 * haga una operación global pasa primero por ahí).
 *
 * <p>La baja global (estado INACTIVO o BLOQUEADO) no toca las membresías
 * de grupo del usuario (HU-01): {@code ServicioAutorizacion} decide el
 * acceso a cada grupo por la membresía activa, no por este estado global.
 *
 * <p>Nunca puede quedar el sistema sin un administrador ACTIVO: quitarle
 * el rol o desactivar al último administrador activo lanza {@link
 * ExcepcionReglaNegocio}. Esa regla se evalúa bloqueando antes las filas de
 * los administradores activos ({@code
 * RepositorioUsuario#bloquearAdministradoresEn}, {@code SELECT ... FOR
 * UPDATE}), para que dos administradores que se quitan el rol (o se dan de
 * baja) casi al mismo tiempo no puedan pasar ambos el chequeo (TOCTOU).
 */
@Service
public class ServicioAdministracionUsuarios {

    private static final String MODULO = "usuarios";
    private static final String TIPO_ENTIDAD = "USUARIO";
    private static final String TABLA_ESTADO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String ACCION_ACTUALIZAR_USUARIO = "ADMIN_ACTUALIZAR_USUARIO";
    private static final String ACCION_CAMBIAR_ESTADO = "ADMIN_CAMBIAR_ESTADO_USUARIO";
    private static final String MENSAJE_USUARIO_NO_EXISTE = "El usuario no existe";
    private static final String MENSAJE_SIN_ADMINISTRADOR_ACTIVO = "Debe existir al menos un administrador activo";

    private final RepositorioUsuario repositorio;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioAdministracionUsuarios(RepositorioUsuario repositorio, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Usuarios cuyo nombre o email contienen {@code texto} (sin distinguir
     * mayúsculas), ordenados por nombre; {@code texto} nulo o en blanco no
     * filtra.
     *
     * @throws ExcepcionAccesoDenegado quien llama no es administrador.
     */
    public Page<Usuario> listar(String texto, int pagina, int tamano) {
        servicioAutorizacion.exigirAdministrador();
        String textoNormalizado = (texto == null || texto.isBlank()) ? null : texto.trim();
        return repositorio.buscarPorTexto(textoNormalizado, PageRequest.of(pagina, tamano));
    }

    /**
     * @throws ExcepcionAccesoDenegado quien llama no es administrador.
     * @throws ExcepcionNoEncontrado   el usuario no existe.
     */
    public Usuario obtener(long idUsuario) {
        servicioAutorizacion.exigirAdministrador();
        return buscarPorId(idUsuario);
    }

    /**
     * Actualiza nombre, teléfono y rol de administrador de {@code
     * idUsuario}, y audita los campos cambiados.
     *
     * @throws ExcepcionAccesoDenegado quien llama no es administrador.
     * @throws ExcepcionNoEncontrado   el usuario no existe.
     * @throws ExcepcionReglaNegocio   quita el rol al último administrador activo.
     * @throws ExcepcionCampoInvalido  el teléfono cambió y no es chileno (si es el mismo ya
     *                                 guardado, no se valida: {@link TelefonoChileno#aGuardar}).
     */
    @Transactional
    public Usuario actualizar(long idUsuario, String nombre, String telefono, boolean esAdministrador) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        Usuario usuario = buscarPorId(idUsuario);

        String nombreAnterior = usuario.getNombre();
        String telefonoAnterior = usuario.getTelefono();
        boolean esAdministradorAnterior = Boolean.TRUE.equals(usuario.getEsAdministrador());

        boolean seguiraSiendoAdministradorActivo = esAdministrador && usuario.getIdEstado() == idEstadoActivo();
        exigirQuedeAlMenosUnAdministradorActivo(usuario, seguiraSiendoAdministradorActivo);

        String telefonoAGuardar = TelefonoChileno.aGuardar(telefono, telefonoAnterior);
        usuario.actualizarDesdeAdministracion(nombre, telefonoAGuardar, esAdministrador, OffsetDateTime.now(reloj));
        Usuario guardado = repositorio.save(usuario);

        List<String> camposCambiados = new ArrayList<>();
        if (!Objects.equals(nombreAnterior, guardado.getNombre())) {
            camposCambiados.add("nombre");
        }
        if (!Objects.equals(telefonoAnterior, guardado.getTelefono())) {
            camposCambiados.add("telefono");
        }
        if (esAdministradorAnterior != Boolean.TRUE.equals(guardado.getEsAdministrador())) {
            camposCambiados.add("esAdministrador");
        }

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdUsuario()),
                null, administrador.getIdUsuario(), ACCION_ACTUALIZAR_USUARIO, ResultadoAuditoria.EXITO,
                Map.of("campos", camposCambiados)));

        return guardado;
    }

    /**
     * Cambia el estado global de {@code idUsuario}. La baja (INACTIVO o
     * BLOQUEADO) no toca sus membresías de grupo (HU-01).
     *
     * @throws ExcepcionAccesoDenegado quien llama no es administrador.
     * @throws ExcepcionNoEncontrado   el usuario no existe.
     * @throws ExcepcionReglaNegocio   {@code idEstadoNuevo} no es de {@code
     *                                 USUARIO.ESTADO}, o desactiva al
     *                                 último administrador activo.
     */
    @Transactional
    public Usuario cambiarEstado(long idUsuario, long idEstadoNuevo) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        servicioCatalogos.validarEstado(idEstadoNuevo, TABLA_ESTADO, CAMPO_ESTADO);
        Usuario usuario = buscarPorId(idUsuario);
        long idEstadoAnterior = usuario.getIdEstado();

        boolean seguiraSiendoAdministradorActivo =
                Boolean.TRUE.equals(usuario.getEsAdministrador()) && idEstadoNuevo == idEstadoActivo();
        exigirQuedeAlMenosUnAdministradorActivo(usuario, seguiraSiendoAdministradorActivo);

        usuario.cambiarEstado(idEstadoNuevo, OffsetDateTime.now(reloj));
        Usuario guardado = repositorio.save(usuario);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdUsuario()),
                null, administrador.getIdUsuario(), ACCION_CAMBIAR_ESTADO, ResultadoAuditoria.EXITO,
                Map.of("idEstadoAnterior", idEstadoAnterior, "idEstadoNuevo", idEstadoNuevo)));

        return guardado;
    }

    /**
     * Si {@code usuario} es hoy administrador ACTIVO y, tras la operación en
     * curso, {@code seguiraSiendoAdministradorActivo} es falso, exige que
     * quede al menos otro administrador ACTIVO.
     *
     * <p>El conteo se hace sobre {@code
     * RepositorioUsuario#bloquearAdministradoresEn}, que bloquea las filas
     * de los administradores activos ({@code SELECT ... FOR UPDATE}) antes
     * de contarlas: si dos administradores se quitan el rol (o se dan de
     * baja) casi al mismo tiempo, la segunda transacción que llega a este
     * punto queda bloqueada hasta que la primera confirma, y al reanudar
     * vuelve a evaluar la condición ya con el cambio de la primera
     * confirmado (evita la carrera de dos conteos sin bloqueo pasando
     * ambos antes de que ninguno confirme).
     */
    private void exigirQuedeAlMenosUnAdministradorActivo(Usuario usuario, boolean seguiraSiendoAdministradorActivo) {
        boolean esHoyAdministradorActivo =
                Boolean.TRUE.equals(usuario.getEsAdministrador()) && usuario.getIdEstado() == idEstadoActivo();
        if (!esHoyAdministradorActivo || seguiraSiendoAdministradorActivo) {
            return;
        }
        if (repositorio.bloquearAdministradoresEn(idEstadoActivo()).size() <= 1) {
            throw new ExcepcionReglaNegocio(MENSAJE_SIN_ADMINISTRADOR_ACTIVO);
        }
    }

    private Usuario buscarPorId(long idUsuario) {
        return repositorio.findById(idUsuario).orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_USUARIO_NO_EXISTE));
    }

    private long idEstadoActivo() {
        return servicioCatalogos.idEstado(TABLA_ESTADO, CAMPO_ESTADO, CODIGO_ACTIVO);
    }
}
