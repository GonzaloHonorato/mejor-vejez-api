package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.RespuestaEstado;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Confirma la ejecución de una tarea de cuidado o la cancela (HU-07,
 * TSA-40): cambio de estado con las transiciones de la regla 10, el permiso
 * de la regla 11 y la observación obligatoria donde corresponde, más su
 * historial de estados y su auditoría.
 *
 * <p><b>Bloqueo (regla 6).</b> Solo la fila de la tarea ({@link
 * RepositorioTareaCuidado#bloquearPorId}); este servicio nunca bloquea el
 * adulto ni el plan.
 *
 * <p><b>Orden de validaciones</b> (Tarea 6 de la Fase 4): membresía (403) →
 * existencia y pertenencia al grupo (404) → el {@code idEstado} pertenece al
 * dominio {@code TAREA_CUIDADO} (422) → permiso de la regla 11 (403) →
 * {@code CANCELADA} en una tarea {@code MEDICAMENTOS} (422, regla 5) →
 * transición válida de la regla 10 (422) → observación obligatoria en
 * {@code NO_REALIZADA}/{@code CANCELADA} (422) → motivo de cancelación de
 * hasta 200 caracteres (422).
 */
@Service
public class ServicioEstadoTareas {

    static final String MENSAJE_SOLO_COORDINADOR_CANCELA = "Solo el coordinador puede cancelar tareas";
    static final String MENSAJE_SOLO_RESPONSABLE_O_COORDINADOR =
            "Solo el responsable asignado o el coordinador puede actualizar esta tarea";
    static final String MENSAJE_MEDICAMENTO_NO_SE_CANCELA =
            "Las tareas de medicamentos se cancelan modificando el plan";
    static final String MENSAJE_OBSERVACION_OBLIGATORIA = "Indica una observación";
    static final String MENSAJE_MOTIVO_CANCELACION_MAXIMO = "El motivo de cancelación admite hasta 200 caracteres";

    private static final int MAXIMO_CARACTERES_MOTIVO_CANCELACION = 200;

    private static final String TABLA_ESTADO_TAREA = CodigosTareas.TABLA_TAREA_CUIDADO;
    private static final String TABLA_ESTADO_ASIGNACION = CodigosTareas.TABLA_ASIGNACION_TAREA;
    private static final String CAMPO_ESTADO = CodigosTareas.CAMPO_ESTADO;
    /** Mismo texto que {@code ServicioCatalogos.validarEstado}, para el estado desactivado del catálogo. */
    static final String MENSAJE_ESTADO_NO_VALIDO =
            "El estado indicado no corresponde a " + TABLA_ESTADO_TAREA + "." + CAMPO_ESTADO;

    /** Transiciones válidas de la regla 10 de la Fase 4 (TSA-40); lo que no está aquí es inválido. */
    private static final Map<String, Set<String>> TRANSICIONES_VALIDAS = Map.of(
            CodigosTareas.ESTADO_TAREA_PENDIENTE,
            Set.of(CodigosTareas.ESTADO_TAREA_EN_CURSO, CodigosTareas.ESTADO_TAREA_COMPLETADA,
                    CodigosTareas.ESTADO_TAREA_NO_REALIZADA, CodigosTareas.ESTADO_TAREA_CANCELADA),
            CodigosTareas.ESTADO_TAREA_EN_CURSO,
            Set.of(CodigosTareas.ESTADO_TAREA_COMPLETADA, CodigosTareas.ESTADO_TAREA_NO_REALIZADA));

    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioAsignacionTarea repositorioAsignacion;
    private final RepositorioHistorialEstadoTarea repositorioHistorial;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final EnsambladorRespuestasTarea ensamblador;
    private final Clock reloj;

    public ServicioEstadoTareas(RepositorioTareaCuidado repositorioTarea,
            RepositorioAsignacionTarea repositorioAsignacion, RepositorioHistorialEstadoTarea repositorioHistorial,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, EnsambladorRespuestasTarea ensamblador, Clock reloj) {
        this.repositorioTarea = repositorioTarea;
        this.repositorioAsignacion = repositorioAsignacion;
        this.repositorioHistorial = repositorioHistorial;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.ensamblador = ensamblador;
        this.reloj = reloj;
    }

    /**
     * Cambia el estado de la tarea (confirma su ejecución o la cancela).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo, o no
     *                                 cumple el permiso de la regla 11 para este cambio.
     * @throws ExcepcionNoEncontrado   la tarea no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   {@code idEstado} no pertenece al dominio {@code TAREA_CUIDADO} o está
     *                                 desactivado en el catálogo, la
     *                                 cancelación es de una tarea {@code MEDICAMENTOS}, la transición no es válida,
     *                                 falta la observación obligatoria, o el motivo de cancelación supera los 200
     *                                 caracteres.
     */
    @Transactional
    public RespuestaTarea cambiarEstado(long idGrupo, long idTarea, SolicitudCambiarEstadoTarea solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        TareaCuidado tarea = bloquearTareaDelGrupo(idGrupo, idTarea);
        servicioCatalogos.validarEstado(solicitud.idEstado(), TABLA_ESTADO_TAREA, CAMPO_ESTADO);

        Map<Long, RespuestaEstado> estadosTarea = servicioCatalogos.listarEstados(TABLA_ESTADO_TAREA, CAMPO_ESTADO)
                .stream().collect(Collectors.toMap(RespuestaEstado::idEstado, Function.identity()));
        RespuestaEstado estadoActual = estadosTarea.get(tarea.getIdEstado());
        RespuestaEstado estadoNuevo = estadosTarea.get(solicitud.idEstado());
        if (estadoActual == null || estadoNuevo == null) {
            // Anexo 3: el estado debe existir, ser del dominio y estar ACTIVO
            // (listarEstados solo trae los activos).
            throw new ExcepcionReglaNegocio(MENSAJE_ESTADO_NO_VALIDO);
        }
        String codigoNuevo = estadoNuevo.codigo();
        boolean esCancelacion = CodigosTareas.ESTADO_TAREA_CANCELADA.equals(codigoNuevo);

        exigirPermiso(contexto, tarea, esCancelacion);

        if (esCancelacion && CodigosTareas.TIPO_TAREA_MEDICAMENTOS.equals(tarea.getTipoTarea())) {
            throw new ExcepcionReglaNegocio(MENSAJE_MEDICAMENTO_NO_SE_CANCELA);
        }

        exigirTransicionValida(estadoActual, estadoNuevo);

        String observacion = solicitud.observacion();
        boolean exigeObservacion = esCancelacion || CodigosTareas.ESTADO_TAREA_NO_REALIZADA.equals(codigoNuevo);
        if (exigeObservacion && observacion == null) {
            throw new ExcepcionReglaNegocio(MENSAJE_OBSERVACION_OBLIGATORIA);
        }
        if (esCancelacion && observacion != null
                && observacion.length() > MAXIMO_CARACTERES_MOTIVO_CANCELACION) {
            throw new ExcepcionReglaNegocio(MENSAJE_MOTIVO_CANCELACION_MAXIMO);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long idEstadoAnterior = tarea.getIdEstado();
        long idEstadoNuevo = solicitud.idEstado();
        long idActor = contexto.usuario().getIdUsuario();

        aplicarCambio(tarea, codigoNuevo, idEstadoNuevo, idActor, observacion, ahora);
        repositorioTarea.save(tarea);

        repositorioHistorial.save(
                new HistorialEstadoTarea(idTarea, idEstadoAnterior, idEstadoNuevo, idActor, observacion, ahora));

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idEstadoAnterior", idEstadoAnterior);
        detalles.put("idEstadoNuevo", idEstadoNuevo);
        servicioAuditoria.registrar(new EventoARegistrar(CodigosTareas.MODULO, CodigosTareas.TIPO_ENTIDAD_TAREA,
                String.valueOf(idTarea), idGrupo, idActor, AccionesTareas.CAMBIAR_ESTADO_TAREA,
                ResultadoAuditoria.EXITO, DetallesAuditoria.conTrazabilidad(contexto, detalles)));

        return ensamblador.ensamblar(tarea);
    }

    /**
     * Aplica el cambio sobre la entidad: {@code EN_CURSO} es una transición
     * simple; {@code COMPLETADA}/{@code NO_REALIZADA} confirman (el actor
     * queda como confirmador); {@code CANCELADA} cancela y termina la
     * asignación {@code ACTIVO} vigente, si la hay (pasa a {@code CANCELADA}).
     */
    private void aplicarCambio(TareaCuidado tarea, String codigoNuevo, long idEstadoNuevo, long idActor,
            String observacion, OffsetDateTime ahora) {
        if (CodigosTareas.ESTADO_TAREA_EN_CURSO.equals(codigoNuevo)) {
            tarea.cambiarEstado(idEstadoNuevo, ahora);
            return;
        }
        if (CodigosTareas.ESTADO_TAREA_COMPLETADA.equals(codigoNuevo)
                || CodigosTareas.ESTADO_TAREA_NO_REALIZADA.equals(codigoNuevo)) {
            tarea.confirmar(idEstadoNuevo, idActor, observacion, ahora);
            return;
        }
        tarea.cancelar(idEstadoNuevo, observacion, ahora);
        long idAsignacionActiva = idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
        repositorioAsignacion.findByIdTareaAndIdEstado(tarea.getIdTarea(), idAsignacionActiva).ifPresent(
                asignacion -> {
                    asignacion.terminar(idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_CANCELADA), ahora);
                    repositorioAsignacion.save(asignacion);
                });
    }

    /**
     * Regla 11 de la Fase 4: cancelar es solo del coordinador o el
     * administrador. Para cualquier otro cambio, el coordinador y el
     * administrador siempre pueden; si no, solo puede el responsable de la
     * asignación {@code ACTIVO} de la tarea, o cualquier integrante (ya
     * exigido activo por {@code exigirMiembro}) si no tiene asignación
     * activa.
     */
    private void exigirPermiso(ContextoGrupo contexto, TareaCuidado tarea, boolean esCancelacion) {
        boolean coordinadorOAdministrador = contexto.esAdministrador() || RolGrupo.COORDINADOR.equals(contexto.rol());
        if (esCancelacion) {
            if (!coordinadorOAdministrador) {
                throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_COORDINADOR_CANCELA);
            }
            return;
        }
        if (coordinadorOAdministrador) {
            return;
        }
        long idAsignacionActiva = idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
        boolean puedeActualizar = repositorioAsignacion.findByIdTareaAndIdEstado(tarea.getIdTarea(), idAsignacionActiva)
                .map(asignacion -> asignacion.getIdUsuarioAsignado().longValue()
                        == contexto.usuario().getIdUsuario().longValue())
                .orElse(true);
        if (!puedeActualizar) {
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_RESPONSABLE_O_COORDINADOR);
        }
    }

    /** @throws ExcepcionReglaNegocio la transición {@code actual → nuevo} no está en la regla 10. */
    private void exigirTransicionValida(RespuestaEstado actual, RespuestaEstado nuevo) {
        Set<String> permitidos = TRANSICIONES_VALIDAS.getOrDefault(actual.codigo(), Set.of());
        if (!permitidos.contains(nuevo.codigo())) {
            throw new ExcepcionReglaNegocio(
                    "No se puede pasar de «" + actual.descripcion() + "» a «" + nuevo.descripcion() + "»");
        }
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

    private long idEstadoAsignacion(String codigo) {
        return servicioCatalogos.idEstado(TABLA_ESTADO_ASIGNACION, CAMPO_ESTADO, codigo);
    }
}
