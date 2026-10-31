package cl.adndigital.mejorvejez.grupos;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Crear, listar, ver y editar grupos familiares. La autorización por grupo
 * (membresía/coordinador) la exige {@link ServicioAutorizacion} en cada
 * operación, igual que el resto de los servicios de grupo (ver javadoc ahí).
 *
 * <p>Al crear, el creador queda como integrante COORDINADOR ACTIVO del grupo
 * nuevo (HU-01): esta es la única forma de obtener la primera membresía de un
 * grupo, ya que no existe un flujo de alta sin creador.
 */
@Service
public class ServicioGrupos {

    private static final String MODULO = "grupos";
    private static final String TIPO_ENTIDAD = "GRUPO_FAMILIAR";
    private static final String TABLA_ESTADO_GRUPO = "GRUPO_FAMILIAR";
    private static final String TABLA_ESTADO_INTEGRANTE = "INTEGRANTE_GRUPO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String ACCION_CREAR_GRUPO = "CREAR_GRUPO";
    private static final String ACCION_ACTUALIZAR_GRUPO = "ACTUALIZAR_GRUPO";

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioRolGrupo repositorioRol;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioUsuarioActual servicioUsuarioActual;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioGrupos(RepositorioGrupoFamiliar repositorioGrupo, RepositorioIntegranteGrupo repositorioIntegrante,
            RepositorioRolGrupo repositorioRol, ServicioAutorizacion servicioAutorizacion,
            ServicioUsuarioActual servicioUsuarioActual, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioRol = repositorioRol;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioUsuarioActual = servicioUsuarioActual;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Crea el grupo ACTIVO, con notificación al coordinador activada, y deja
     * al usuario autenticado como integrante COORDINADOR ACTIVO. Audita
     * {@code CREAR_GRUPO}.
     */
    @Transactional
    public RespuestaGrupo crear(String nombreGrupo) {
        return crearConCreador(nombreGrupo, servicioUsuarioActual.obtener());
    }

    /**
     * Misma creación que {@link #crear(String)}, pero con {@code creador}
     * explícito en vez del usuario autenticado: lo reutiliza el registro
     * propio (HU-01), que crea el grupo en la misma transacción del alta de
     * la cuenta, antes de que exista un usuario autenticado en la petición.
     */
    @Transactional
    public RespuestaGrupo crearConCreador(String nombreGrupo, Usuario creador) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);

        long idEstadoActivoGrupo = servicioCatalogos.idEstado(TABLA_ESTADO_GRUPO, CAMPO_ESTADO, CODIGO_ACTIVO);
        GrupoFamiliar grupo = repositorioGrupo.save(
                new GrupoFamiliar(nombreGrupo.trim(), true, creador.getIdUsuario(), idEstadoActivoGrupo, ahora));

        long idRolCoordinador = idRol(RolGrupo.COORDINADOR);
        long idEstadoActivoIntegrante = servicioCatalogos.idEstado(TABLA_ESTADO_INTEGRANTE, CAMPO_ESTADO, CODIGO_ACTIVO);
        repositorioIntegrante.save(new IntegranteGrupo(grupo.getIdGrupo(), creador.getIdUsuario(), idRolCoordinador,
                idEstadoActivoIntegrante, ahora));

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(grupo.getIdGrupo()),
                grupo.getIdGrupo(), creador.getIdUsuario(), ACCION_CREAR_GRUPO, ResultadoAuditoria.EXITO,
                Map.of("nombreGrupo", grupo.getNombreGrupo())));

        return RespuestaGrupo.desde(grupo, RolGrupo.COORDINADOR);
    }

    /**
     * Sin {@code todos}: grupos con membresía ACTIVA del usuario autenticado,
     * ordenados por nombre. Con {@code todos}: todos los grupos (solo
     * administrador).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado {@code
     *                                                                 todos}
     *                                                                 pedido
     *                                                                 por
     *                                                                 quien
     *                                                                 no es
     *                                                                 administrador.
     */
    public List<RespuestaGrupo> listar(boolean todos) {
        Usuario usuario = servicioUsuarioActual.obtener();
        if (todos) {
            servicioAutorizacion.exigirAdministrador();
        }

        Map<Long, String> miRolPorGrupo = rolPorGrupoActivo(usuario.getIdUsuario());
        List<GrupoFamiliar> grupos = todos
                ? repositorioGrupo.findAllByOrderByNombreGrupoAsc()
                : repositorioGrupo.findAllById(miRolPorGrupo.keySet()).stream()
                        .sorted((a, b) -> a.getNombreGrupo().compareToIgnoreCase(b.getNombreGrupo()))
                        .toList();

        return grupos.stream()
                .map(grupo -> RespuestaGrupo.desde(grupo, miRolPorGrupo.get(grupo.getIdGrupo())))
                .toList();
    }

    /** @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es integrante activo del grupo. */
    public RespuestaGrupo obtener(long idGrupo) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        return RespuestaGrupo.desde(contexto.grupo(), contexto.rol());
    }

    /**
     * Actualiza nombre y notificación al coordinador, y audita {@code
     * ACTUALIZAR_GRUPO}.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     */
    @Transactional
    public RespuestaGrupo actualizar(long idGrupo, String nombreGrupo, boolean notificaCoordinador) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        GrupoFamiliar grupo = contexto.grupo();

        String nombreAnterior = grupo.getNombreGrupo();
        boolean notificaAnterior = Boolean.TRUE.equals(grupo.getNotificaCoordinador());

        grupo.actualizar(nombreGrupo.trim(), notificaCoordinador, OffsetDateTime.now(reloj));
        GrupoFamiliar guardado = repositorioGrupo.save(grupo);

        List<String> camposCambiados = new ArrayList<>();
        if (!Objects.equals(nombreAnterior, guardado.getNombreGrupo())) {
            camposCambiados.add("nombreGrupo");
        }
        if (notificaAnterior != Boolean.TRUE.equals(guardado.getNotificaCoordinador())) {
            camposCambiados.add("notificaCoordinador");
        }

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(guardado.getIdGrupo()),
                guardado.getIdGrupo(), contexto.usuario().getIdUsuario(), ACCION_ACTUALIZAR_GRUPO,
                ResultadoAuditoria.EXITO, Map.of("campos", camposCambiados)));

        return RespuestaGrupo.desde(guardado, contexto.rol());
    }

    private long idRol(String nombreRol) {
        return repositorioRol.findByNombreRol(nombreRol)
                .orElseThrow(() -> new IllegalStateException("No existe el rol de grupo '" + nombreRol + "'"))
                .getIdRol();
    }

    /** Rol de la membresía ACTIVA del usuario en cada grupo donde la tiene. */
    private Map<Long, String> rolPorGrupoActivo(long idUsuario) {
        long idEstadoActivo = servicioCatalogos.idEstado(TABLA_ESTADO_INTEGRANTE, CAMPO_ESTADO, CODIGO_ACTIVO);
        List<IntegranteGrupo> membresias = repositorioIntegrante.findByIdUsuarioAndIdEstado(idUsuario, idEstadoActivo);
        if (membresias.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> rolesPorId = repositorioRol.findAll().stream()
                .collect(Collectors.toMap(RolGrupo::getIdRol, RolGrupo::getNombreRol));
        return membresias.stream()
                .collect(Collectors.toMap(IntegranteGrupo::getIdGrupo, membresia -> rolesPorId.get(membresia.getIdRol())));
    }
}
