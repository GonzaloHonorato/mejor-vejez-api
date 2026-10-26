package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.EventoCambioEstadoAdulto;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.planificacion.CatalogoDiasSemana;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.EventoCambioPlanificacion;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;

/**
 * Recálculo síncrono de las tareas de medicamentos cuando cambia un plan, un
 * detalle o el estado de un adulto mayor (HU-05, TSA-38, regla 8 de la Fase
 * 4). Escucha {@link EventoCambioPlanificacion} y {@link
 * EventoCambioEstadoAdulto} dentro de la transacción del cambio
 * ({@code MANDATORY}): una excepción aquí revierte el cambio que lo originó.
 * Para cada detalle afectado:
 * <ul>
 * <li><b>Cancelar:</b> las ocurrencias futuras ({@code instante > ahora})
 * que ya no corresponden (plan, detalle, adulto y grupo ACTIVO y {@link
 * PlanificadorOcurrencias#esperado}). PLANIFICADA o ERROR pasan a CANCELADA.
 * TAREA_CREADA con su tarea PENDIENTE: la tarea pasa a CANCELADA con {@link
 * #MOTIVO_CANCELACION}, su asignación activa a CANCELADA, se escribe el
 * historial con el actor y la auditoría {@code
 * CANCELAR_TAREA_POR_CAMBIO_PLAN}; la ocurrencia sigue TAREA_CREADA. EN_CURSO
 * y las finalizadas no se tocan.</li>
 * <li><b>Ajustar:</b> las PENDIENTE futuras que siguen correspondiendo toman
 * el título y la descripción vigentes del detalle. El responsable no cambia
 * (HU-06).</li>
 * <li><b>Generar:</b> los turnos libres de {@code [ahora, fin de ventana)}
 * con {@link CreadorTareas#reservarSiLibre} y {@link
 * CreadorTareas#crearTarea}, que participan en esta transacción. Nunca
 * {@link CreadorTareas#crearDesdeOcurrencia}: es {@code REQUIRES_NEW} y
 * bloquea el plan, que esta transacción ya tiene bloqueado.</li>
 * </ul>
 *
 * <p><b>Bloqueos (regla 6).</b> Orden adulto → plan → tarea: el recálculo
 * por adulto bloquea primero el adulto y luego cada uno de sus planes por id
 * ascendente; las tareas futuras de cada detalle se bloquean en una sola
 * consulta por id ascendente, con el plan ya bloqueado, antes de leer su
 * estado.
 */
@Service
public class ServicioRecalculoTareas {

    /** Motivo exacto de la cancelación por recálculo (regla 8). */
    static final String MOTIVO_CANCELACION = "Cancelada por un cambio en el plan de medicamentos";

    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final RepositorioDetallePlan repositorioDetalles;
    private final RepositorioAdultoMayor repositorioAdultos;
    private final RepositorioOcurrenciaPlanificada repositorioOcurrencias;
    private final RepositorioTareaCuidado repositorioTareas;
    private final RepositorioAsignacionTarea repositorioAsignaciones;
    private final RepositorioHistorialEstadoTarea repositorioHistorial;
    private final VerificadorVigencia verificador;
    private final CreadorTareas creadorTareas;
    private final PlanificadorOcurrencias planificador;
    private final CatalogoDiasSemana catalogoDias;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;
    private final int diasVentana;

    public ServicioRecalculoTareas(RepositorioPlanMedicamentos repositorioPlanes,
            RepositorioDetallePlan repositorioDetalles, RepositorioAdultoMayor repositorioAdultos,
            RepositorioOcurrenciaPlanificada repositorioOcurrencias, RepositorioTareaCuidado repositorioTareas,
            RepositorioAsignacionTarea repositorioAsignaciones, RepositorioHistorialEstadoTarea repositorioHistorial,
            VerificadorVigencia verificador, CreadorTareas creadorTareas, PlanificadorOcurrencias planificador,
            CatalogoDiasSemana catalogoDias, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj,
            @Value("${mejorvejez.tareas.dias-ventana}") int diasVentana) {
        this.repositorioPlanes = repositorioPlanes;
        this.repositorioDetalles = repositorioDetalles;
        this.repositorioAdultos = repositorioAdultos;
        this.repositorioOcurrencias = repositorioOcurrencias;
        this.repositorioTareas = repositorioTareas;
        this.repositorioAsignaciones = repositorioAsignaciones;
        this.repositorioHistorial = repositorioHistorial;
        this.verificador = verificador;
        this.creadorTareas = creadorTareas;
        this.planificador = planificador;
        this.catalogoDias = catalogoDias;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
        this.diasVentana = diasVentana;
    }

    /**
     * Cambio de plan o de detalles. El llamador ({@code ServicioPlanes} /
     * {@code ServicioDetallesPlan}) ya tiene el plan bloqueado; se relee con
     * {@code bloquearPorId}, que es reentrante en la misma transacción y
     * devuelve la instancia gestionada (con los cambios aún sin confirmar).
     * Si {@code idsDetalle} está vacío se recalculan todos los detalles del
     * plan, en cualquier estado.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void alCambiarPlanificacion(EventoCambioPlanificacion e) {
        PlanMedicamentos plan = repositorioPlanes.bloquearPorId(e.idPlan()).orElseThrow();
        List<DetallePlan> detalles = e.idsDetalle().isEmpty()
                ? repositorioDetalles.findByIdPlanOrderByIdDetallePlan(e.idPlan())
                : repositorioDetalles.findAllById(e.idsDetalle()).stream()
                        .filter(d -> d.getIdPlan().longValue() == e.idPlan())
                        .sorted(Comparator.comparing(DetallePlan::getIdDetallePlan))
                        .toList();
        recalcular(plan, detalles, e.idUsuarioActor(), e.accesoAdministrador());
    }

    /**
     * Desactivación o reactivación de un adulto mayor: bloquea el adulto
     * (primero, regla 6) y luego cada plan del adulto por id ascendente, y
     * recalcula todos sus detalles.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void alCambiarEstadoAdulto(EventoCambioEstadoAdulto e) {
        repositorioAdultos.bloquearPorId(e.idAdulto()).orElseThrow();
        List<Long> idsPlan = repositorioPlanes
                .findByIdGrupoAndIdAdultoMayorOrderByFechaCreacionDescIdPlanDesc(e.idGrupo(), e.idAdulto()).stream()
                .map(PlanMedicamentos::getIdPlan)
                .sorted()
                .toList();
        for (long idPlan : idsPlan) {
            PlanMedicamentos plan = repositorioPlanes.bloquearPorId(idPlan).orElseThrow();
            recalcular(plan, repositorioDetalles.findByIdPlanOrderByIdDetallePlan(idPlan), e.idUsuarioActor(),
                    e.accesoAdministrador());
        }
    }

    /**
     * Aplica la regla 8 (cancelar, ajustar, generar) a los detalles indicados
     * del plan. Participa en la transacción del llamador, que debe tener el
     * plan bloqueado. Con {@code accesoAdministrador}, cada auditoría de
     * cancelación lleva {@code "accesoAdministrador": true} ({@link
     * DetallesAuditoria#conTrazabilidad(boolean, Map)}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalcular(PlanMedicamentos plan, List<DetallePlan> detalles, long idUsuarioActor,
            boolean accesoAdministrador) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime finVentana = finVentana();
        boolean planVigente = verificador.planAdultoYGrupoActivos(plan);
        for (DetallePlan detalle : detalles) {
            Optional<Programacion> programacion = planVigente && verificador.detalleActivo(detalle)
                    ? ProgramacionDetalle.de(detalle, plan, catalogoDias)
                    : Optional.empty();
            cancelarYAjustar(plan, detalle, programacion, ahora, idUsuarioActor, accesoAdministrador);
            programacion.ifPresent(p -> generar(plan, detalle, p, ahora, finVentana));
        }
    }

    /** Cancelar y Ajustar de la regla 8, sobre las ocurrencias futuras del detalle. */
    private void cancelarYAjustar(PlanMedicamentos plan, DetallePlan detalle, Optional<Programacion> programacion,
            OffsetDateTime ahora, long idUsuarioActor, boolean accesoAdministrador) {
        // Instante > ahora (regla 8): un turno exactamente en "ahora" no se
        // cancela, aunque Generar sí lo considera ([ahora, fin)) si está libre.
        List<OcurrenciaPlanificada> futuras = repositorioOcurrencias
                .findByIdDetallePlanAndFechaHorarioAfterOrderByFechaHorario(detalle.getIdDetallePlan(), ahora);
        if (futuras.isEmpty()) {
            return;
        }
        long idTareaCreada = estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_TAREA_CREADA);
        long idPlanificada = estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA);
        long idError = estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_ERROR);
        long idOcurrenciaCancelada = estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_CANCELADA);

        List<Long> idsTarea = futuras.stream()
                .filter(o -> o.getIdEstado() == idTareaCreada && o.getIdTarea() != null)
                .map(OcurrenciaPlanificada::getIdTarea)
                .toList();
        Map<Long, TareaCuidado> tareas = idsTarea.isEmpty() ? Map.of()
                : repositorioTareas.bloquearPorIds(idsTarea).stream()
                        .collect(Collectors.toMap(TareaCuidado::getIdTarea, Function.identity()));

        String titulo = null;
        String descripcion = null;
        long idPendiente = estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        for (OcurrenciaPlanificada ocurrencia : futuras) {
            boolean corresponde = programacion
                    .map(p -> planificador.esperado(p, ocurrencia.getFechaHorario()))
                    .orElse(false);
            long idEstado = ocurrencia.getIdEstado();
            if (idEstado == idPlanificada || idEstado == idError) {
                if (!corresponde) {
                    ocurrencia.cambiarEstado(idOcurrenciaCancelada);
                    repositorioOcurrencias.save(ocurrencia);
                }
                continue;
            }
            if (idEstado != idTareaCreada || ocurrencia.getIdTarea() == null) {
                continue;
            }
            TareaCuidado tarea = tareas.get(ocurrencia.getIdTarea());
            if (tarea == null || tarea.getIdEstado() != idPendiente) {
                continue;
            }
            if (!corresponde) {
                cancelarTarea(plan, detalle, tarea, ahora, idUsuarioActor, accesoAdministrador);
                continue;
            }
            if (titulo == null) {
                titulo = creadorTareas.titulo(detalle);
                descripcion = creadorTareas.descripcion(detalle);
            }
            if (!Objects.equals(tarea.getTituloTarea(), titulo)
                    || !Objects.equals(tarea.getDescripcionTarea(), descripcion)) {
                tarea.ajustarTexto(titulo, descripcion, ahora);
                repositorioTareas.save(tarea);
            }
        }
    }

    /**
     * PENDIENTE → CANCELADA con el motivo de la regla 8, asignación activa
     * CANCELADA (con fecha de término), historial con el actor y auditoría
     * {@code CANCELAR_TAREA_POR_CAMBIO_PLAN} (solo ids y códigos).
     */
    private void cancelarTarea(PlanMedicamentos plan, DetallePlan detalle, TareaCuidado tarea, OffsetDateTime ahora,
            long idUsuarioActor, boolean accesoAdministrador) {
        long idPendiente = tarea.getIdEstado();
        long idCancelada = estadoTarea(CodigosTareas.ESTADO_TAREA_CANCELADA);
        tarea.cancelar(idCancelada, MOTIVO_CANCELACION, ahora);
        repositorioTareas.save(tarea);

        repositorioAsignaciones.findByIdTareaAndIdEstado(tarea.getIdTarea(),
                estadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_ACTIVO)).ifPresent(asignacion -> {
                    asignacion.terminar(estadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_CANCELADA), ahora);
                    repositorioAsignaciones.save(asignacion);
                });

        repositorioHistorial.save(new HistorialEstadoTarea(tarea.getIdTarea(), idPendiente, idCancelada,
                idUsuarioActor, MOTIVO_CANCELACION, ahora));

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idPlan", plan.getIdPlan());
        detalles.put("idDetallePlan", detalle.getIdDetallePlan());
        detalles.put("idEstadoAnterior", idPendiente);
        detalles.put("idEstadoNuevo", idCancelada);
        servicioAuditoria.registrar(new EventoARegistrar(CodigosTareas.MODULO, CodigosTareas.TIPO_ENTIDAD_TAREA,
                String.valueOf(tarea.getIdTarea()), plan.getIdGrupo(), idUsuarioActor,
                AccionesTareas.CANCELAR_TAREA_POR_CAMBIO_PLAN, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(accesoAdministrador, detalles)));
    }

    /** Generar de la regla 8: los turnos libres de {@code [ahora, finVentana)}. */
    private void generar(PlanMedicamentos plan, DetallePlan detalle, Programacion programacion,
            OffsetDateTime ahora, OffsetDateTime finVentana) {
        for (OffsetDateTime horario : planificador.horarios(programacion, ahora, finVentana)) {
            creadorTareas.reservarSiLibre(detalle, plan, horario, ahora)
                    .ifPresent(ocurrencia -> creadorTareas.crearTarea(detalle, plan, ocurrencia, ahora));
        }
    }

    /** 00:00 (America/Santiago) del día {@code hoy + dias-ventana}: el mismo fin que la corrida diaria (regla 1). */
    private OffsetDateTime finVentana() {
        LocalDate hoy = LocalDate.now(reloj.withZone(ZonaNegocio.ZONA));
        return ZonedDateTime.of(hoy.plusDays(diasVentana), LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private long estadoOcurrencia(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_OCURRENCIA_PLANIFICADA, CodigosTareas.CAMPO_ESTADO,
                codigo);
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long estadoAsignacion(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CodigosTareas.CAMPO_ESTADO, codigo);
    }
}
