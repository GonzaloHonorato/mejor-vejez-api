package cl.adndigital.mejorvejez.seguridad;

import java.util.Map;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.grupos.GrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.grupos.RepositorioRolGrupo;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Único punto que decide el acceso por grupo. Todo servicio que lea o
 * modifique datos de un grupo debe pasar primero por {@link
 * #exigirMiembro(long)} o {@link #exigirCoordinador(long)}; las operaciones
 * globales, por {@link #exigirAdministrador()}.
 *
 * <p>Reglas: solo una membresía en estado ACTIVO da acceso (INACTIVO y
 * SALIDA_PENDIENTE no); el administrador global pasa siempre, tenga o no
 * membresía. Cada rechazo queda auditado como RECHAZADA en una transacción
 * propia, para que el evento persista aunque la transacción del llamador
 * haga rollback por la excepción.
 *
 * <p>No es {@code @Transactional}: participa en la transacción del llamador
 * si la hay (p.ej. para que el grupo devuelto quede administrado por ella).
 */
@Service
public class ServicioAutorizacion {

    static final String ACCION_ACCESO_GRUPO = "ACCESO_GRUPO";
    static final String ACCION_ACCESO_ADMINISTRADOR = "ACCESO_ADMINISTRADOR";

    private static final String MODULO = "seguridad";
    private static final String MENSAJE_NO_MIEMBRO = "No perteneces a este grupo";
    private static final String MENSAJE_NO_COORDINADOR = "Solo un coordinador puede realizar esta acción";
    private static final String MENSAJE_NO_ADMINISTRADOR = "Solo un administrador puede realizar esta acción";

    private final ServicioUsuarioActual servicioUsuarioActual;
    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioRolGrupo repositorioRol;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;

    public ServicioAutorizacion(ServicioUsuarioActual servicioUsuarioActual,
            RepositorioGrupoFamiliar repositorioGrupo, RepositorioIntegranteGrupo repositorioIntegrante,
            RepositorioRolGrupo repositorioRol, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria) {
        this.servicioUsuarioActual = servicioUsuarioActual;
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioRol = repositorioRol;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
    }

    /**
     * Exige que el usuario actual sea integrante ACTIVO del grupo (o
     * administrador).
     *
     * @throws ExcepcionNoEncontrado   el grupo no existe.
     * @throws ExcepcionAccesoDenegado no tiene membresía activa (auditado).
     */
    public ContextoGrupo exigirMiembro(long idGrupo) {
        ContextoGrupo contexto = resolver(idGrupo);
        if (!contexto.esAdministrador() && contexto.rol() == null) {
            rechazar(contexto, "SIN_MEMBRESIA_ACTIVA");
            throw new ExcepcionAccesoDenegado(MENSAJE_NO_MIEMBRO);
        }
        return contexto;
    }

    /**
     * Exige que el usuario actual sea COORDINADOR activo del grupo (o
     * administrador). Si ni siquiera es integrante activo, el rechazo es el
     * de {@link #exigirMiembro(long)}.
     *
     * @throws ExcepcionNoEncontrado   el grupo no existe.
     * @throws ExcepcionAccesoDenegado no es coordinador activo (auditado).
     */
    public ContextoGrupo exigirCoordinador(long idGrupo) {
        ContextoGrupo contexto = exigirMiembro(idGrupo);
        if (!contexto.esAdministrador() && !RolGrupo.COORDINADOR.equals(contexto.rol())) {
            rechazar(contexto, "NO_ES_COORDINADOR");
            throw new ExcepcionAccesoDenegado(MENSAJE_NO_COORDINADOR);
        }
        return contexto;
    }

    /**
     * Exige que el usuario actual sea administrador global.
     *
     * @throws ExcepcionAccesoDenegado no es administrador (auditado).
     */
    public Usuario exigirAdministrador() {
        Usuario usuario = servicioUsuarioActual.obtener();
        if (!Boolean.TRUE.equals(usuario.getEsAdministrador())) {
            servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(MODULO, "USUARIO",
                    String.valueOf(usuario.getIdUsuario()), null, usuario.getIdUsuario(),
                    ACCION_ACCESO_ADMINISTRADOR, ResultadoAuditoria.RECHAZADA,
                    Map.of("motivo", "NO_ES_ADMINISTRADOR")));
            throw new ExcepcionAccesoDenegado(MENSAJE_NO_ADMINISTRADOR);
        }
        return usuario;
    }

    /** Usuario, grupo y rol de su membresía ACTIVA (o {@code null} si no la tiene). */
    private ContextoGrupo resolver(long idGrupo) {
        Usuario usuario = servicioUsuarioActual.obtener();
        GrupoFamiliar grupo = repositorioGrupo.findById(idGrupo)
                .orElseThrow(() -> new ExcepcionNoEncontrado("El grupo no existe"));
        long idActivo = servicioCatalogos.idEstado("INTEGRANTE_GRUPO", "ESTADO", "ACTIVO");
        String rol = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, usuario.getIdUsuario())
                .filter(integrante -> integrante.getIdEstado() == idActivo)
                .flatMap(integrante -> repositorioRol.findById(integrante.getIdRol()))
                .map(RolGrupo::getNombreRol)
                .orElse(null);
        return new ContextoGrupo(usuario, grupo, rol, Boolean.TRUE.equals(usuario.getEsAdministrador()));
    }

    private void rechazar(ContextoGrupo contexto, String motivo) {
        long idGrupo = contexto.grupo().getIdGrupo();
        long idUsuario = contexto.usuario().getIdUsuario();
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(MODULO, "GRUPO_FAMILIAR",
                String.valueOf(idGrupo), idGrupo, idUsuario, ACCION_ACCESO_GRUPO,
                ResultadoAuditoria.RECHAZADA, Map.of("motivo", motivo)));
    }
}
