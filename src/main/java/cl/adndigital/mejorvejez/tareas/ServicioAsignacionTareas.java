package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Asigna o reasigna el responsable de una tarea de cuidado (HU-06, TSA-39):
 * solo el coordinador o el administrador pueden hacerlo, y el responsable
 * debe ser integrante {@code ACTIVO} del grupo. La asignación {@code
 * ACTIVO} vigente (si la hay) pasa a {@code REEMPLAZADA} y se crea una
 * nueva {@code ACTIVO} con el motivo indicado; todo queda auditado con el
 * responsable anterior y el nuevo.
 *
 * <p><b>Bloqueo (regla 6 de la Fase 4).</b> Solo la fila de la tarea
 * ({@link RepositorioTareaCuidado#bloquearPorId}); este servicio nunca
 * bloquea el adulto ni el plan, y serializa así cualquier (re)asignación
 * concurrente de la misma tarea.
 *
 * <p><b>Asignación activa única (restricción global de la Fase 4).</b>
 * Hibernate ejecuta los INSERT antes que los UPDATE al hacer flush: para
 * reemplazar la asignación activa hay que guardar y hacer flush de la
 * anterior ({@code REEMPLAZADA}, con {@link AsignacionTarea#terminar})
 * ANTES de insertar la nueva {@code ACTIVO}. Si no, {@code
 * UX_ASIGNACION_ACTIVA} rechaza el INSERT.
 *
 * <p><b>Orden de validaciones</b> (Tarea 7 de la Fase 4): {@code
 * exigirCoordinador} (403) → existencia y pertenencia al grupo (404) →
 * tarea finalizada (422) → el responsable es integrante {@code ACTIVO} del
 * grupo (422) → el responsable ya es el actual (422).
 */
@Service
public class ServicioAsignacionTareas {

    static final String MENSAJE_TAREA_FINALIZADA = "La tarea ya está finalizada; no se puede reasignar";
    static final String MENSAJE_RESPONSABLE_NO_ACTIVO = "El responsable debe ser integrante activo del grupo";
    static final String MENSAJE_MISMO_RESPONSABLE = "La tarea ya está asignada a ese integrante";

    private static final String TABLA_INTEGRANTE_GRUPO = "INTEGRANTE_GRUPO";
    private static final String CAMPO_ESTADO = CodigosTareas.CAMPO_ESTADO;

    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioAsignacionTarea repositorioAsignacion;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final EnsambladorRespuestasTarea ensamblador;
    private final Clock reloj;

    public ServicioAsignacionTareas(RepositorioTareaCuidado repositorioTarea,
            RepositorioAsignacionTarea repositorioAsignacion, RepositorioIntegranteGrupo repositorioIntegrante,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, EnsambladorRespuestasTarea ensamblador, Clock reloj) {
        this.repositorioTarea = repositorioTarea;
        this.repositorioAsignacion = repositorioAsignacion;
        this.repositorioIntegrante = repositorioIntegrante;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.ensamblador = ensamblador;
        this.reloj = reloj;
    }

    /**
     * Asigna o reemplaza el responsable de la tarea.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo (ni
     *                                 administrador).
     * @throws ExcepcionNoEncontrado   la tarea no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   la tarea ya está finalizada, el responsable indicado no es integrante
     *                                 {@code ACTIVO} del grupo, o ya es el responsable vigente de la tarea.
     */
    @Transactional
    public RespuestaTarea asignar(long idGrupo, long idTarea, SolicitudAsignarTarea solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        TareaCuidado tarea = bloquearTareaDelGrupo(idGrupo, idTarea);

        if (estaFinalizada(tarea.getIdEstado())) {
            throw new ExcepcionReglaNegocio(MENSAJE_TAREA_FINALIZADA);
        }

        long idUsuarioNuevo = solicitud.idUsuario();
        exigirIntegranteActivo(idGrupo, idUsuarioNuevo);

        long idEstadoActivo = idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
        Optional<AsignacionTarea> asignacionActiva =
                repositorioAsignacion.findByIdTareaAndIdEstado(idTarea, idEstadoActivo);

        if (asignacionActiva.isPresent()
                && asignacionActiva.get().getIdUsuarioAsignado().longValue() == idUsuarioNuevo) {
            throw new ExcepcionReglaNegocio(MENSAJE_MISMO_RESPONSABLE);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long idActor = contexto.usuario().getIdUsuario();
        Long idUsuarioAnterior = null;

        if (asignacionActiva.isPresent()) {
            AsignacionTarea anterior = asignacionActiva.get();
            idUsuarioAnterior = anterior.getIdUsuarioAsignado();
            anterior.terminar(idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_REEMPLAZADA), ahora);
            // Hibernate ejecuta los INSERT antes que los UPDATE al hacer flush: hay que confirmar la
            // REEMPLAZADA antes de insertar la nueva ACTIVO, o UX_ASIGNACION_ACTIVA rechaza el INSERT.
            repositorioAsignacion.saveAndFlush(anterior);
        }

        AsignacionTarea nueva = new AsignacionTarea(idTarea, idGrupo, idUsuarioNuevo, idActor, solicitud.motivo(),
                idEstadoActivo, ahora);
        repositorioAsignacion.save(nueva);

        registrarAuditoria(contexto, idGrupo, idTarea, idActor, idUsuarioAnterior, idUsuarioNuevo);

        return ensamblador.ensamblar(tarea);
    }

    /** Auditoría {@code ASIGNAR_TAREA} (sin anterior) o {@code REASIGNAR_TAREA} con ambos responsables. */
    private void registrarAuditoria(ContextoGrupo contexto, long idGrupo, long idTarea, long idActor,
            Long idUsuarioAnterior, long idUsuarioNuevo) {
        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idUsuarioNuevo", idUsuarioNuevo);
        String accion;
        if (idUsuarioAnterior == null) {
            accion = AccionesTareas.ASIGNAR_TAREA;
        } else {
            accion = AccionesTareas.REASIGNAR_TAREA;
            detalles.put("idUsuarioAnterior", idUsuarioAnterior);
        }
        servicioAuditoria.registrar(new EventoARegistrar(CodigosTareas.MODULO, CodigosTareas.TIPO_ENTIDAD_TAREA,
                String.valueOf(idTarea), idGrupo, idActor, accion, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    /** @throws ExcepcionReglaNegocio el usuario no tiene membresía {@code ACTIVO} en el grupo. */
    private void exigirIntegranteActivo(long idGrupo, long idUsuario) {
        long idActivo = servicioCatalogos.idEstado(TABLA_INTEGRANTE_GRUPO, CAMPO_ESTADO, "ACTIVO");
        boolean esIntegranteActivo = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idUsuario)
                .filter(integrante -> integrante.getIdEstado().longValue() == idActivo)
                .isPresent();
        if (!esIntegranteActivo) {
            throw new ExcepcionReglaNegocio(MENSAJE_RESPONSABLE_NO_ACTIVO);
        }
    }

    /** Regla 10 de la Fase 4: {@code COMPLETADA}, {@code NO_REALIZADA} y {@code CANCELADA} son finales. */
    private boolean estaFinalizada(long idEstadoTarea) {
        return idEstadoTarea == idEstadoTareaCuidado(CodigosTareas.ESTADO_TAREA_COMPLETADA)
                || idEstadoTarea == idEstadoTareaCuidado(CodigosTareas.ESTADO_TAREA_NO_REALIZADA)
                || idEstadoTarea == idEstadoTareaCuidado(CodigosTareas.ESTADO_TAREA_CANCELADA);
    }

    /** @throws ExcepcionNoEncontrado la tarea no existe o no pertenece al grupo. */
    private TareaCuidado bloquearTareaDelGrupo(long idGrupo, long idTarea) {
        TareaCuidado tarea = repositorioTarea.bloquearPorId(idTarea)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioConsultaTareas.MENSAJE_NO_EXISTE));
        if (tarea.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioConsultaTareas.MENSAJE_NO_EXISTE);
        }
        return tarea;
    }

    private long idEstadoTareaCuidado(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CAMPO_ESTADO, codigo);
    }

    private long idEstadoAsignacion(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CAMPO_ESTADO, codigo);
    }
}
