package cl.adndigital.mejorvejez.grupos;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Incorporar, listar, cambiar el rol, desactivar, reactivar y eliminar
 * integrantes de un grupo; y que un integrante salga de un grupo (HU-02). La
 * autorización por grupo la exige {@link ServicioAutorizacion} en cada
 * operación, igual que {@code ServicioGrupos}.
 *
 * <p><b>Último coordinador.</b> Un grupo nunca puede quedar sin al menos un
 * COORDINADOR ACTIVO: cambiarle el rol a COLABORADOR, desactivarlo,
 * eliminarlo o que salga del grupo lanza {@link ExcepcionReglaNegocio} si es
 * el único coordinador activo que queda. Para que dos mutaciones concurrentes
 * sobre el mismo grupo (p.ej. dos coordinadores degradándose el uno al otro a
 * la vez) no dejen pasar a ambas antes de que ninguna confirme (TOCTOU), toda
 * mutación de integrantes bloquea primero la fila del grupo con {@code
 * RepositorioGrupoFamiliar#bloquearPorId} ({@code SELECT ... FOR UPDATE}):
 * la segunda transacción que llega queda esperando hasta que la primera
 * confirma, y al reanudar vuelve a evaluar la condición con los datos ya
 * confirmados (mismo patrón que {@code
 * ServicioAdministracionUsuarios#exigirQuedeAlMenosUnAdministradorActivo},
 * que bloquea las filas de los administradores en vez de la del grupo).
 *
 * <p><b>Orden bloqueo→autorización.</b> El bloqueo del grupo se toma SIEMPRE
 * antes de llamar a {@code ServicioAutorizacion} (y antes de leer cualquier
 * fila de {@code INTEGRANTE_GRUPO}): la primera lectura de la membresía del
 * actor (dentro de {@code exigirMiembro}/{@code exigirCoordinador}) debe
 * ocurrir ya con el bloqueo tomado, para que esa fila entre al contexto de
 * persistencia (caché de primer nivel de Hibernate) con los datos ya
 * confirmados por la transacción anterior. Si se autorizara antes de
 * bloquear, una transacción que espera el {@code FOR UPDATE} podría quedarse
 * con una copia vieja de su propia membresía en la sesión (p.ej. su rol
 * ANTES de que otra transacción la ascendiera a COORDINADOR) y usarla luego
 * para una regla de negocio que ya no corresponde al dato confirmado.
 *
 * <p><b>Reincorporación (INC-22).</b> Incorporar por correo a alguien con una
 * membresía INACTIVA existente reactiva la misma fila (no crea una nueva):
 * {@code UQ_INTEGRANTE_GRUPO} es única por (grupo, usuario).
 */
@Service
public class ServicioIntegrantes {

    private static final String MODULO = "grupos";
    private static final String TIPO_ENTIDAD = "INTEGRANTE_GRUPO";
    private static final String TABLA_ESTADO_INTEGRANTE = "INTEGRANTE_GRUPO";
    private static final String TABLA_ESTADO_USUARIO = "USUARIO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CODIGO_INACTIVO = "INACTIVO";

    private static final String ACCION_INCORPORAR_INTEGRANTE = "INCORPORAR_INTEGRANTE";
    private static final String ACCION_REACTIVAR_INTEGRANTE = "REACTIVAR_INTEGRANTE";
    private static final String ACCION_CAMBIAR_ROL_INTEGRANTE = "CAMBIAR_ROL_INTEGRANTE";
    private static final String ACCION_DESACTIVAR_INTEGRANTE = "DESACTIVAR_INTEGRANTE";
    private static final String ACCION_SALIR_GRUPO = "SALIR_GRUPO";
    private static final String ACCION_ELIMINAR_INTEGRANTE = "ELIMINAR_INTEGRANTE";

    private static final String MENSAJE_INTEGRANTE_NO_EXISTE = "El integrante no existe";
    private static final String MENSAJE_SIN_CUENTA = "No existe una cuenta con ese correo";
    private static final String MENSAJE_CUENTA_INACTIVA = "La cuenta indicada no está activa";
    private static final String MENSAJE_ROL_NO_EXISTE = "El rol indicado no existe";
    private static final String MENSAJE_YA_ES_INTEGRANTE = "La persona ya es integrante del grupo";
    private static final String MENSAJE_SIN_COORDINADOR_ACTIVO = "El grupo debe conservar al menos un coordinador activo";
    private static final String MENSAJE_NO_ES_MIEMBRO = "No perteneces a este grupo";
    private static final String MENSAJE_TIENE_HISTORIAL = "La membresía tiene historial; desactívala en su lugar";

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioRolGrupo repositorioRol;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioHistorialIntegrante repositorioHistorial;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioIntegrantes(RepositorioGrupoFamiliar repositorioGrupo,
            RepositorioIntegranteGrupo repositorioIntegrante, RepositorioRolGrupo repositorioRol,
            RepositorioUsuario repositorioUsuario, RepositorioHistorialIntegrante repositorioHistorial,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioRol = repositorioRol;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioHistorial = repositorioHistorial;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Todos los integrantes del grupo (en cualquier estado): activos
     * primero, y luego por fecha de incorporación.
     *
     * @throws ExcepcionAccesoDenegado no es integrante activo del grupo.
     */
    public List<RespuestaIntegrante> listar(long idGrupo) {
        servicioAutorizacion.exigirMiembro(idGrupo);

        List<IntegranteGrupo> integrantes = repositorioIntegrante.findByIdGrupoOrderByFechaIncorporacion(idGrupo);
        long idEstadoActivo = idEstadoActivoIntegrante();
        List<IntegranteGrupo> ordenados = integrantes.stream()
                .sorted(Comparator.comparing(integrante -> integrante.getIdEstado() != idEstadoActivo))
                .toList();

        Map<Long, Usuario> usuariosPorId = repositorioUsuario
                .findAllById(ordenados.stream().map(IntegranteGrupo::getIdUsuario).toList()).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Function.identity()));
        Map<Long, RolGrupo> rolesPorId = repositorioRol.findAll().stream()
                .collect(Collectors.toMap(RolGrupo::getIdRol, Function.identity()));

        return ordenados.stream()
                .map(integrante -> RespuestaIntegrante.desde(integrante, usuariosPorId.get(integrante.getIdUsuario()),
                        rolesPorId.get(integrante.getIdRol())))
                .toList();
    }

    /**
     * Incorpora por correo (normalizado con {@code trim} + minúsculas) a la
     * cuenta indicada, con el rol dado. Si ya tiene una membresía INACTIVA en
     * el grupo, reactiva esa misma fila con el rol indicado (INC-22) y
     * audita {@code REACTIVAR_INTEGRANTE}; si no tiene ninguna, crea la
     * membresía y audita {@code INCORPORAR_INTEGRANTE}.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionReglaNegocio   no existe cuenta con ese correo, la
     *                                 cuenta no está ACTIVA, o el rol no
     *                                 existe.
     * @throws ExcepcionConflicto      ya es integrante ACTIVO del grupo.
     */
    @Transactional
    public RespuestaIntegrante incorporar(long idGrupo, String email, long idRol) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);

        RolGrupo rol = buscarRol(idRol);
        Usuario usuario = buscarUsuarioActivoPorCorreo(email);

        long idEstadoActivo = idEstadoActivoIntegrante();
        Optional<IntegranteGrupo> existente = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo,
                usuario.getIdUsuario());

        IntegranteGrupo integrante;
        String accion;
        if (existente.isPresent() && existente.get().getIdEstado() == idEstadoActivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_ES_INTEGRANTE);
        } else if (existente.isPresent()) {
            integrante = existente.get();
            integrante.reactivar(idRol, idEstadoActivo);
            accion = ACCION_REACTIVAR_INTEGRANTE;
        } else {
            integrante = new IntegranteGrupo(idGrupo, usuario.getIdUsuario(), idRol, idEstadoActivo,
                    OffsetDateTime.now(reloj));
            accion = ACCION_INCORPORAR_INTEGRANTE;
        }
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdIntegrante()),
                idGrupo, contexto.usuario().getIdUsuario(), accion, ResultadoAuditoria.EXITO,
                Map.of("idUsuario", usuario.getIdUsuario(), "idRol", idRol)));

        return RespuestaIntegrante.desde(guardado, usuario, rol);
    }

    /**
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el integrante no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el rol no existe, o deja al grupo sin
     *                                 coordinador activo.
     */
    @Transactional
    public RespuestaIntegrante cambiarRol(long idGrupo, long idIntegrante, long idRolNuevo) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        IntegranteGrupo integrante = buscarIntegrante(idGrupo, idIntegrante);
        RolGrupo rolNuevo = buscarRol(idRolNuevo);

        boolean seguiraSiendoCoordinadorActivo = integrante.getIdEstado() == idEstadoActivoIntegrante()
                && rolNuevo.getIdRol() == idRolCoordinador();
        exigirQuedeAlMenosUnCoordinadorActivo(integrante, seguiraSiendoCoordinadorActivo);

        long idRolAnterior = integrante.getIdRol();
        integrante.cambiarRol(idRolNuevo);
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdIntegrante()),
                idGrupo, contexto.usuario().getIdUsuario(), ACCION_CAMBIAR_ROL_INTEGRANTE, ResultadoAuditoria.EXITO,
                Map.of("idRolAnterior", idRolAnterior, "idRolNuevo", idRolNuevo)));

        return respuestaDesde(guardado);
    }

    /**
     * Desactivación lógica: conserva la fila e historial, con {@code
     * FECHA_DESACTIVACION} = reloj.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el integrante no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   deja al grupo sin coordinador activo.
     */
    @Transactional
    public RespuestaIntegrante desactivar(long idGrupo, long idIntegrante) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        IntegranteGrupo integrante = buscarIntegrante(idGrupo, idIntegrante);

        exigirQuedeAlMenosUnCoordinadorActivo(integrante, false);

        integrante.desactivar(idEstadoInactivoIntegrante(), OffsetDateTime.now(reloj));
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdIntegrante()),
                idGrupo, contexto.usuario().getIdUsuario(), ACCION_DESACTIVAR_INTEGRANTE, ResultadoAuditoria.EXITO,
                null));

        return respuestaDesde(guardado);
    }

    /**
     * Reactiva la membresía (mismo rol que tenía); limpia {@code
     * FECHA_DESACTIVACION}.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el integrante no pertenece al grupo.
     * @throws ExcepcionConflicto      la membresía ya está ACTIVA.
     * @throws ExcepcionReglaNegocio   la cuenta del integrante no está ACTIVA.
     */
    @Transactional
    public RespuestaIntegrante reactivar(long idGrupo, long idIntegrante) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        IntegranteGrupo integrante = buscarIntegrante(idGrupo, idIntegrante);

        if (integrante.getIdEstado() == idEstadoActivoIntegrante()) {
            throw new ExcepcionConflicto(MENSAJE_YA_ES_INTEGRANTE);
        }
        Usuario usuario = validarActivo(repositorioUsuario.findById(integrante.getIdUsuario())
                .orElseThrow(() -> new IllegalStateException("El usuario de la membresía no existe")));

        integrante.reactivar(integrante.getIdRol(), idEstadoActivoIntegrante());
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdIntegrante()),
                idGrupo, contexto.usuario().getIdUsuario(), ACCION_REACTIVAR_INTEGRANTE, ResultadoAuditoria.EXITO,
                null));

        RolGrupo rol = repositorioRol.findById(guardado.getIdRol())
                .orElseThrow(() -> new IllegalStateException("El rol de la membresía no existe"));
        return RespuestaIntegrante.desde(guardado, usuario, rol);
    }

    /**
     * El usuario autenticado sale del grupo (desactiva su propia membresía).
     * A diferencia de {@link #listar(long)}, no hay bypass de administrador:
     * exige una membresía ACTIVA propia. No toca sus otras membresías.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa propia en el grupo.
     * @throws ExcepcionReglaNegocio   deja al grupo sin coordinador activo.
     */
    @Transactional
    public void salir(long idGrupo) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        Usuario usuario = contexto.usuario();

        IntegranteGrupo integrante = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, usuario.getIdUsuario())
                .filter(propio -> propio.getIdEstado() == idEstadoActivoIntegrante())
                .orElseGet(() -> {
                    auditarRechazoSalir(idGrupo, usuario.getIdUsuario());
                    throw new ExcepcionAccesoDenegado(MENSAJE_NO_ES_MIEMBRO);
                });

        exigirQuedeAlMenosUnCoordinadorActivo(integrante, false);

        integrante.desactivar(idEstadoInactivoIntegrante(), OffsetDateTime.now(reloj));
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdIntegrante()),
                idGrupo, usuario.getIdUsuario(), ACCION_SALIR_GRUPO, ResultadoAuditoria.EXITO, null));
    }

    /**
     * Elimina físicamente la membresía, solo si {@code idUsuario} no dejó
     * historial en el grupo ({@link RepositorioHistorialIntegrante}).
     * Audita {@code ELIMINAR_INTEGRANTE} con el {@code idUsuario} eliminado.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el integrante no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   la membresía tiene historial, o deja al
     *                                 grupo sin coordinador activo.
     */
    @Transactional
    public void eliminar(long idGrupo, long idIntegrante) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        IntegranteGrupo integrante = buscarIntegrante(idGrupo, idIntegrante);

        exigirQuedeAlMenosUnCoordinadorActivo(integrante, false);

        if (repositorioHistorial.tieneHistorial(idGrupo, integrante.getIdUsuario())) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIENE_HISTORIAL);
        }

        long idUsuario = integrante.getIdUsuario();
        repositorioIntegrante.delete(integrante);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(idIntegrante), idGrupo,
                contexto.usuario().getIdUsuario(), ACCION_ELIMINAR_INTEGRANTE, ResultadoAuditoria.EXITO,
                Map.of("idUsuario", idUsuario)));
    }

    /** Bloquea la fila del grupo ({@code SELECT ... FOR UPDATE}); ver javadoc de la clase. */
    private GrupoFamiliar bloquearGrupo(long idGrupo) {
        return repositorioGrupo.bloquearPorId(idGrupo)
                .orElseThrow(() -> new ExcepcionNoEncontrado("El grupo no existe"));
    }

    /**
     * Audita el rechazo de {@link #salir(long)} cuando el usuario no tiene
     * membresía ACTIVA propia (p.ej. es administrador sin membresía, o la
     * suya ya está INACTIVA), en transacción propia ({@code REQUIRES_NEW}),
     * igual que {@code ServicioAutorizacion#rechazar}: así el evento persiste
     * aunque la excepción haga rollback de esta transacción.
     */
    private void auditarRechazoSalir(long idGrupo, long idUsuario) {
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(MODULO, "GRUPO_FAMILIAR",
                String.valueOf(idGrupo), idGrupo, idUsuario, ACCION_SALIR_GRUPO, ResultadoAuditoria.RECHAZADA,
                Map.of("motivo", "SIN_MEMBRESIA_ACTIVA")));
    }

    private IntegranteGrupo buscarIntegrante(long idGrupo, long idIntegrante) {
        IntegranteGrupo integrante = repositorioIntegrante.findById(idIntegrante)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_INTEGRANTE_NO_EXISTE));
        if (integrante.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_INTEGRANTE_NO_EXISTE);
        }
        return integrante;
    }

    private RolGrupo buscarRol(long idRol) {
        return repositorioRol.findById(idRol).orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_ROL_NO_EXISTE));
    }

    private Usuario buscarUsuarioActivoPorCorreo(String email) {
        String correoNormalizado = email.trim().toLowerCase(Locale.ROOT);
        Usuario usuario = repositorioUsuario.findByEmail(correoNormalizado)
                .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_SIN_CUENTA));
        return validarActivo(usuario);
    }

    /** @throws ExcepcionReglaNegocio {@code usuario} no está en estado ACTIVO. */
    private Usuario validarActivo(Usuario usuario) {
        long idEstadoActivoUsuario = servicioCatalogos.idEstado(TABLA_ESTADO_USUARIO, CAMPO_ESTADO, CODIGO_ACTIVO);
        if (usuario.getIdEstado() != idEstadoActivoUsuario) {
            throw new ExcepcionReglaNegocio(MENSAJE_CUENTA_INACTIVA);
        }
        return usuario;
    }

    private RespuestaIntegrante respuestaDesde(IntegranteGrupo integrante) {
        Usuario usuario = repositorioUsuario.findById(integrante.getIdUsuario())
                .orElseThrow(() -> new IllegalStateException("El usuario de la membresía no existe"));
        RolGrupo rol = repositorioRol.findById(integrante.getIdRol())
                .orElseThrow(() -> new IllegalStateException("El rol de la membresía no existe"));
        return RespuestaIntegrante.desde(integrante, usuario, rol);
    }

    /**
     * Si {@code integrante} es hoy COORDINADOR ACTIVO y, tras la operación en
     * curso, {@code seguiraSiendoCoordinadorActivo} es falso, exige que quede
     * al menos otro coordinador ACTIVO en el grupo. El conteo se apoya en el
     * bloqueo de la fila del grupo ya tomado por {@link #bloquearGrupo(long)}
     * (ver javadoc de la clase), no en bloquear las filas de
     * {@code INTEGRANTE_GRUPO}.
     */
    private void exigirQuedeAlMenosUnCoordinadorActivo(IntegranteGrupo integrante, boolean seguiraSiendoCoordinadorActivo) {
        boolean esHoyCoordinadorActivo = integrante.getIdEstado() == idEstadoActivoIntegrante()
                && integrante.getIdRol() == idRolCoordinador();
        if (!esHoyCoordinadorActivo || seguiraSiendoCoordinadorActivo) {
            return;
        }
        long idRolCoordinador = idRolCoordinador();
        long idEstadoActivo = idEstadoActivoIntegrante();
        if (repositorioIntegrante.countByIdGrupoAndIdRolAndIdEstado(integrante.getIdGrupo(), idRolCoordinador,
                idEstadoActivo) <= 1) {
            throw new ExcepcionReglaNegocio(MENSAJE_SIN_COORDINADOR_ACTIVO);
        }
    }

    private long idRolCoordinador() {
        return repositorioRol.findByNombreRol(RolGrupo.COORDINADOR)
                .orElseThrow(() -> new IllegalStateException("No existe el rol de grupo 'COORDINADOR'"))
                .getIdRol();
    }

    private long idEstadoActivoIntegrante() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_INTEGRANTE, CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoInactivoIntegrante() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_INTEGRANTE, CAMPO_ESTADO, CODIGO_INACTIVO);
    }
}
