package cl.adndigital.mejorvejez.alertas;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Detector de alertas preventivas (TSA-44, reglas 3, 4, 5, 6, 10, 11 y 12 de
 * la Fase 5): cita próxima y reposición de un medicamento. Replica el flujo
 * de {@link ServicioDeteccionAtrasos} (TSA-43) en una corrida {@code
 * ALERTAS_PREVENTIVAS}:
 * <ol>
 * <li>registra la {@code EJECUCION_PROCESO} ({@code INICIADA}) con la
 * ventana {@code [ahora, ahora + 24 h]} (regla 3);</li>
 * <li>por cada tarea candidata a cita próxima ({@link
 * RepositorioConsultaAlertas#citasProximasCandidatas}), {@link
 * CreadorAlertas#crearCitaProxima} (una transacción por tarea) crea su
 * alerta si sigue correspondiendo;</li>
 * <li>por cada detalle candidato a reposición ({@link
 * RepositorioConsultaAlertas#detallesPorReponer}), {@link
 * CreadorAlertas#crearReposicion} (una transacción por detalle) crea su
 * alerta si sigue correspondiendo;</li>
 * <li>"notificar pendientes" ({@link
 * ServicioNotificacionAlertas#notificarPendientes}), con el mismo umbral
 * ({@code ahora − horas-retrospectiva}) que usa la detección de atrasos
 * (regla 10: cubre "los dos procesos");</li>
 * <li>cierra la corrida ({@link CorridaAlertas#cerrar}, regla 11). Los
 * errores son las alertas que fallaron al crearse, los intentos {@code
 * FALLIDO}, las alertas creadas en ESTA corrida sin ningún destinatario con
 * canal operativo y los errores inesperados del notificador.</li>
 * </ol>
 *
 * <p>No es {@code @Transactional}: cada alerta y cada notificación abre la
 * suya ({@code REQUIRES_NEW}). Es idempotente: reejecutarla, o correr dos a
 * la vez, no duplica alertas. El resumen nunca incluye destinos (correos ni
 * teléfonos) ni el mensaje crudo de una excepción al crear una alerta (regla
 * 11: {@code Tarea {id}: {claseSimple}} o {@code Detalle {id}: {claseSimple}};
 * el mensaje completo va solo al log). El cierre de la corrida, el fallo del
 * proceso y la auditoría de la corrida manual viven en {@link
 * CorridaAlertas}, compartido con {@link ServicioDeteccionAtrasos} (Ronda de
 * corrección 1 de la Tarea 6: antes estaban duplicados).
 */
@Service
public class ServicioAlertasPreventivas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioAlertasPreventivas.class);
    private static final int HORAS_VENTANA_CITAS = 24;

    private final RegistroEjecuciones registro;
    private final CreadorAlertas creadorAlertas;
    private final RepositorioConsultaAlertas consultaAlertas;
    private final RepositorioAlertaCuidado repositorioAlertas;
    private final RepositorioDetallePlan repositorioDetalles;
    private final ServicioNotificacionAlertas servicioNotificacion;
    private final ServicioAutorizacion servicioAutorizacion;
    private final CorridaAlertas corridaAlertas;
    private final Clock reloj;
    private final int horasRetrospectiva;

    ServicioAlertasPreventivas(RegistroEjecuciones registro, CreadorAlertas creadorAlertas,
            RepositorioConsultaAlertas consultaAlertas, RepositorioAlertaCuidado repositorioAlertas,
            RepositorioDetallePlan repositorioDetalles, ServicioNotificacionAlertas servicioNotificacion,
            ServicioAutorizacion servicioAutorizacion, CorridaAlertas corridaAlertas, Clock reloj,
            @Value("${mejorvejez.alertas.horas-retrospectiva}") int horasRetrospectiva) {
        this.registro = registro;
        this.creadorAlertas = creadorAlertas;
        this.consultaAlertas = consultaAlertas;
        this.repositorioAlertas = repositorioAlertas;
        this.repositorioDetalles = repositorioDetalles;
        this.servicioNotificacion = servicioNotificacion;
        this.servicioAutorizacion = servicioAutorizacion;
        this.corridaAlertas = corridaAlertas;
        this.reloj = reloj;
        this.horasRetrospectiva = horasRetrospectiva;
    }

    /** Corrida programada ({@link ProgramadorAlertas}, cada hora en Santiago) sobre todos los grupos. */
    public RespuestaEjecucion ejecutarProgramado() {
        return ejecutar(OffsetDateTime.now(reloj), null);
    }

    /**
     * Corrida manual del administrador ({@code POST
     * /api/administracion/procesos/alertas-preventivas}): la misma que la
     * programada, sobre todos los grupos, auditada por {@link
     * CorridaAlertas#ejecutarManualAuditado} como {@code
     * EJECUTAR_ALERTAS_PREVENTIVAS}.
     */
    public RespuestaEjecucion ejecutarManual() {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return corridaAlertas.ejecutarManualAuditado(administrador, AccionesAlertas.EJECUTAR_ALERTAS_PREVENTIVAS,
                () -> registro.iniciar(CodigosAlertas.TIPO_EJECUCION_ALERTAS_PREVENTIVAS, ahora,
                        ahora.plusHours(HORAS_VENTANA_CITAS)),
                idEjecucion -> procesar(idEjecucion, ahora, null));
    }

    /**
     * Una corrida en {@code ahora}, de todos los grupos o solo de {@code
     * idGrupo} (las pruebas siempre lo pasan: la BD es compartida).
     */
    RespuestaEjecucion ejecutar(OffsetDateTime ahora, Long idGrupo) {
        return procesar(registro.iniciar(CodigosAlertas.TIPO_EJECUCION_ALERTAS_PREVENTIVAS, ahora,
                ahora.plusHours(HORAS_VENTANA_CITAS)), ahora, idGrupo);
    }

    /**
     * Cuerpo de la corrida sobre una {@code EJECUCION_PROCESO} ya {@code
     * INICIADA}: primero las citas próximas, luego las reposiciones y por
     * último "notificar pendientes". Un error al crear una alerta se
     * acumula y la corrida sigue. Si falla algo fuera de esos pasos, {@link
     * CorridaAlertas#registrarFallo} cierra la corrida como FALLIDA y
     * relanza.
     */
    private RespuestaEjecucion procesar(long idEjecucion, OffsetDateTime ahora, Long idGrupo) {
        List<String> errores = new ArrayList<>();
        Set<Long> creadasEnEstaCorrida = new HashSet<>();
        ResultadoNotificacion notificacion;
        try {
            OffsetDateTime hasta = ahora.plusHours(HORAS_VENTANA_CITAS);
            LocalDate hoy = ahora.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
            for (long idTarea : consultaAlertas.citasProximasCandidatas(ahora, hasta, idGrupo)) {
                crearCitaProxima(idTarea, ahora, creadasEnEstaCorrida, errores);
            }
            for (long idDetalle : consultaAlertas.detallesPorReponer(hoy, idGrupo)) {
                crearReposicion(idDetalle, hoy, ahora, creadasEnEstaCorrida, errores);
            }
            notificacion = servicioNotificacion.notificarPendientes(desdeNotificacion(ahora), idGrupo);
        } catch (RuntimeException ex) {
            corridaAlertas.registrarFallo(idEjecucion, creadasEnEstaCorrida, errores, ex);
            throw ex;
        }
        return corridaAlertas.cerrar(idEjecucion, creadasEnEstaCorrida, errores, notificacion);
    }

    /**
     * Crea la alerta de cita próxima de una tarea. Si choca con {@code
     * UQ_ALERTA_CLAVE} (otra corrida la creó a la vez), cuenta como "ya
     * existía", no como error (regla 5).
     */
    private void crearCitaProxima(long idTarea, OffsetDateTime ahora, Set<Long> creadas, List<String> errores) {
        try {
            Optional<Long> idAlerta = creadorAlertas.crearCitaProxima(idTarea, ahora);
            idAlerta.ifPresent(creadas::add);
        } catch (DataIntegrityViolationException ex) {
            if (!yaExisteCita(idTarea)) {
                LOG.warn("Alertas preventivas: falló la creación de la alerta de cita de la tarea {}", idTarea, ex);
                errores.add("Tarea " + idTarea + ": " + ex.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            LOG.warn("Alertas preventivas: falló la creación de la alerta de cita de la tarea {}", idTarea, ex);
            errores.add("Tarea " + idTarea + ": " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Crea la alerta de reposición de un detalle. Si choca con {@code
     * UQ_ALERTA_CLAVE} (otra corrida la creó a la vez), cuenta como "ya
     * existía", no como error (regla 5).
     */
    private void crearReposicion(long idDetalle, LocalDate hoy, OffsetDateTime ahora, Set<Long> creadas,
            List<String> errores) {
        try {
            Optional<Long> idAlerta = creadorAlertas.crearReposicion(idDetalle, hoy, ahora);
            idAlerta.ifPresent(creadas::add);
        } catch (DataIntegrityViolationException ex) {
            if (!yaExisteReposicion(idDetalle)) {
                LOG.warn("Alertas preventivas: falló la creación de la alerta de reposición del detalle {}",
                        idDetalle, ex);
                errores.add("Detalle " + idDetalle + ": " + ex.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            LOG.warn("Alertas preventivas: falló la creación de la alerta de reposición del detalle {}", idDetalle,
                    ex);
            errores.add("Detalle " + idDetalle + ": " + ex.getClass().getSimpleName());
        }
    }

    private boolean yaExisteCita(long idTarea) {
        try {
            return repositorioAlertas.findByClaveAlerta(CreadorAlertas.claveCitaProxima(idTarea)).isPresent();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Si la alerta ya existe con la clave que corresponde al término
     * vigente del detalle (la única que la transacción que chocó pudo haber
     * usado).
     */
    private boolean yaExisteReposicion(long idDetalle) {
        try {
            return repositorioDetalles.findById(idDetalle)
                    .map(DetallePlan::getFechaEstimadaTermino)
                    .map(termino -> CreadorAlertas.claveReposicion(idDetalle, termino))
                    .flatMap(repositorioAlertas::findByClaveAlerta)
                    .isPresent();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Regla 10: el mismo umbral que la detección de atrasos para "notificar pendientes" de los dos procesos. */
    private OffsetDateTime desdeNotificacion(OffsetDateTime ahora) {
        return ahora.minusHours(horasRetrospectiva);
    }
}
