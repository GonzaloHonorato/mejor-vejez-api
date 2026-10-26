package cl.adndigital.mejorvejez.alertas;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Bandeja de alertas del grupo y detalle de una alerta (HU-08, TSA-47).
 * Solo lectura: la autorización exige únicamente membresía activa del grupo
 * ({@link ServicioAutorizacion#exigirMiembro(long)}, regla 14 de la Fase 5);
 * ni {@code listar} ni {@code obtener} auditan (igual que {@code
 * tareas.ServicioConsultaTareas}).
 *
 * <p><b>Sin N+1.</b> El ensamblado de la respuesta lo delega por completo en
 * {@link EnsambladorRespuestasAlerta}.
 */
@Service
public class ServicioConsultaAlertas {

    static final String MENSAJE_NO_EXISTE = "La alerta no existe";
    static final String MENSAJE_TIPO_NO_EXISTE = "El tipo de alerta no existe.";

    private static final String TABLA_ESTADO_ALERTA = CodigosAlertas.TABLA_ALERTA_CUIDADO;
    private static final String CAMPO_ESTADO = CodigosAlertas.CAMPO_ESTADO;

    /** Dominio de {@code ALERTA_CUIDADO.TIPO_ALERTA} (regla 18 de la Fase 5: fuera de estos tres, 422). */
    private static final Set<String> TIPOS_VALIDOS = Set.of(CodigosAlertas.TIPO_ATRASO_TAREA,
            CodigosAlertas.TIPO_CITA_PROXIMA, CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO);

    private final RepositorioAlertaCuidado repositorioAlerta;
    private final RepositorioIntentoNotificacion repositorioIntento;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final EnsambladorRespuestasAlerta ensamblador;

    public ServicioConsultaAlertas(RepositorioAlertaCuidado repositorioAlerta,
            RepositorioIntentoNotificacion repositorioIntento, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, EnsambladorRespuestasAlerta ensamblador) {
        this.repositorioAlerta = repositorioAlerta;
        this.repositorioIntento = repositorioIntento;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.ensamblador = ensamblador;
    }

    /**
     * Bandeja del grupo (TSA-47, regla 18 de la Fase 5), filtrada
     * opcionalmente por estado, tipo y adulto, paginada y ordenada por fecha
     * de detección descendente (y luego por id, también descendente). Un
     * {@code idAdulto} de otro grupo no es un error: la consulta, filtrada
     * siempre por {@code idGrupo}, simplemente no encuentra nada.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionReglaNegocio   {@code idEstado} no pertenece al dominio {@code ALERTA_CUIDADO}, o {@code
     *                                 tipoAlerta} no es uno de los tres tipos válidos.
     */
    @Transactional(readOnly = true)
    public RespuestaPagina<RespuestaAlerta> listar(long idGrupo, Long idEstado, String tipoAlerta, Long idAdulto,
            int pagina, int tamano) {
        servicioAutorizacion.exigirMiembro(idGrupo);

        if (idEstado != null) {
            servicioCatalogos.validarEstado(idEstado, TABLA_ESTADO_ALERTA, CAMPO_ESTADO);
        }
        if (tipoAlerta != null && !TIPOS_VALIDOS.contains(tipoAlerta)) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIPO_NO_EXISTE);
        }

        Pageable pageable = PageRequest.of(pagina, tamano);
        Page<AlertaCuidado> paginaResultado = repositorioAlerta.buscar(idGrupo, idEstado, tipoAlerta, idAdulto,
                pageable);
        List<RespuestaAlerta> elementos = ensamblador.ensamblar(paginaResultado.getContent());
        return new RespuestaPagina<>(elementos, pagina, tamano, paginaResultado.getTotalElements());
    }

    /**
     * El detalle de la alerta, con su historial de intentos de notificación.
     * El {@code detalleError} de cada intento solo viaja si quien consulta es
     * coordinador o administrador (regla 14 de la Fase 5); los demás lo
     * reciben {@code null}.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   la alerta no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaAlertaDetallada obtener(long idGrupo, long idAlerta) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        AlertaCuidado alerta = buscarAlerta(idGrupo, idAlerta);
        List<IntentoNotificacion> intentos = repositorioIntento
                .findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(idAlerta);
        boolean incluyeDetalleError = contexto.esAdministrador() || RolGrupo.COORDINADOR.equals(contexto.rol());
        return ensamblador.detallar(alerta, intentos, incluyeDetalleError);
    }

    /** @throws ExcepcionNoEncontrado la alerta no existe o no pertenece al grupo. */
    private AlertaCuidado buscarAlerta(long idGrupo, long idAlerta) {
        AlertaCuidado alerta = repositorioAlerta.findById(idAlerta)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (alerta.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return alerta;
    }
}
