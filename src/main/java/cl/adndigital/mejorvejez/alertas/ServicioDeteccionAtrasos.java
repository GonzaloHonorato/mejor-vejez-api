package cl.adndigital.mejorvejez.alertas;

import java.time.Clock;
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

import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Detector de atrasos (TSA-43, reglas 2, 5, 10, 11 y 12 de la Fase 5). Una
 * corrida:
 * <ol>
 * <li>registra la {@code EJECUCION_PROCESO} {@code DETECCION_ATRASOS}
 * ({@code INICIADA}) con la ventana {@code [ahora − horas-retrospectiva,
 * ahora]};</li>
 * <li>por cada tarea candidata ({@link
 * RepositorioConsultaAlertas#tareasAtrasadasCandidatas}), {@link
 * CreadorAlertas#crearAtraso} (una transacción por tarea) crea su alerta si
 * sigue correspondiendo;</li>
 * <li>"notificar pendientes" ({@link
 * ServicioNotificacionAlertas#notificarPendientes}): las alertas nuevas y
 * los reintentos;</li>
 * <li>cierra la corrida ({@link CorridaAlertas#cerrar}, regla 11). Los
 * errores son las alertas que fallaron al crearse, los intentos {@code
 * FALLIDO}, las alertas creadas en ESTA corrida sin ningún destinatario con
 * canal operativo y los errores inesperados del notificador.</li>
 * </ol>
 *
 * <p>No es {@code @Transactional}: cada alerta y cada notificación abre la
 * suya ({@code REQUIRES_NEW}), igual que {@code
 * tareas.ServicioGeneracionTareas}. Es idempotente: reejecutarla, o correr
 * dos a la vez, no duplica alertas. El resumen nunca incluye destinos
 * (correos ni teléfonos). El cierre de la corrida, el fallo del proceso y la
 * auditoría de la corrida manual viven en {@link CorridaAlertas}, compartido
 * con {@link ServicioAlertasPreventivas} (Ronda de corrección 1 de la Tarea
 * 6: antes estaban duplicados).
 */
@Service
public class ServicioDeteccionAtrasos {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioDeteccionAtrasos.class);

    private final RegistroEjecuciones registro;
    private final CreadorAlertas creadorAlertas;
    private final RepositorioConsultaAlertas consultaAlertas;
    private final RepositorioAlertaCuidado repositorioAlertas;
    private final ServicioNotificacionAlertas servicioNotificacion;
    private final ServicioAutorizacion servicioAutorizacion;
    private final CorridaAlertas corridaAlertas;
    private final Clock reloj;
    private final int horasRetrospectiva;

    ServicioDeteccionAtrasos(RegistroEjecuciones registro, CreadorAlertas creadorAlertas,
            RepositorioConsultaAlertas consultaAlertas, RepositorioAlertaCuidado repositorioAlertas,
            ServicioNotificacionAlertas servicioNotificacion, ServicioAutorizacion servicioAutorizacion,
            CorridaAlertas corridaAlertas, Clock reloj,
            @Value("${mejorvejez.alertas.horas-retrospectiva}") int horasRetrospectiva) {
        this.registro = registro;
        this.creadorAlertas = creadorAlertas;
        this.consultaAlertas = consultaAlertas;
        this.repositorioAlertas = repositorioAlertas;
        this.servicioNotificacion = servicioNotificacion;
        this.servicioAutorizacion = servicioAutorizacion;
        this.corridaAlertas = corridaAlertas;
        this.reloj = reloj;
        this.horasRetrospectiva = horasRetrospectiva;
    }

    /** Corrida programada ({@link ProgramadorAlertas}, cada 5 minutos en Santiago) sobre todos los grupos. */
    public RespuestaEjecucion ejecutarProgramado() {
        return ejecutar(OffsetDateTime.now(reloj), null);
    }

    /**
     * Corrida manual del administrador ({@code POST
     * /api/administracion/procesos/deteccion-atrasos}): la misma que la
     * programada, sobre todos los grupos, auditada por {@link
     * CorridaAlertas#ejecutarManualAuditado} como {@code
     * EJECUTAR_DETECCION_ATRASOS}.
     */
    public RespuestaEjecucion ejecutarManual() {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime desde = desde(ahora);
        return corridaAlertas.ejecutarManualAuditado(administrador, AccionesAlertas.EJECUTAR_DETECCION_ATRASOS,
                () -> registro.iniciar(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS, desde, ahora),
                idEjecucion -> procesar(idEjecucion, desde, ahora, null));
    }

    /**
     * Una corrida en {@code ahora}, de todos los grupos o solo de {@code
     * idGrupo} (las pruebas siempre lo pasan: la BD es compartida). El grupo
     * filtra tanto las candidatas como el paso "notificar pendientes".
     */
    RespuestaEjecucion ejecutar(OffsetDateTime ahora, Long idGrupo) {
        OffsetDateTime desde = desde(ahora);
        return procesar(registro.iniciar(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS, desde, ahora), desde,
                ahora, idGrupo);
    }

    /**
     * Cuerpo de la corrida sobre una {@code EJECUCION_PROCESO} ya {@code
     * INICIADA}. Un error al crear una alerta se acumula y la corrida sigue.
     * Si falla algo fuera de esos pasos (la consulta de candidatas o el
     * notificador entero), {@link CorridaAlertas#registrarFallo} cierra la
     * corrida como FALLIDA y relanza.
     */
    private RespuestaEjecucion procesar(long idEjecucion, OffsetDateTime desde, OffsetDateTime ahora,
            Long idGrupo) {
        List<String> errores = new ArrayList<>();
        Set<Long> creadasEnEstaCorrida = new HashSet<>();
        ResultadoNotificacion notificacion;
        try {
            for (long idTarea : consultaAlertas.tareasAtrasadasCandidatas(desde, ahora, idGrupo)) {
                crearAtraso(idTarea, ahora, creadasEnEstaCorrida, errores);
            }
            notificacion = servicioNotificacion.notificarPendientes(desde, idGrupo);
        } catch (RuntimeException ex) {
            corridaAlertas.registrarFallo(idEjecucion, creadasEnEstaCorrida, errores, ex);
            throw ex;
        }
        return corridaAlertas.cerrar(idEjecucion, creadasEnEstaCorrida, errores, notificacion);
    }

    /**
     * Crea la alerta de una tarea. Si choca con {@code UQ_ALERTA_CLAVE}
     * (otra corrida la creó a la vez), cuenta como "ya existía", no como
     * error (regla 5).
     */
    private void crearAtraso(long idTarea, OffsetDateTime ahora, Set<Long> creadas, List<String> errores) {
        try {
            Optional<Long> idAlerta = creadorAlertas.crearAtraso(idTarea, ahora);
            idAlerta.ifPresent(creadas::add);
        } catch (DataIntegrityViolationException ex) {
            if (!yaExiste(idTarea)) {
                LOG.warn("Detección de atrasos: falló la creación de la alerta de la tarea {}", idTarea, ex);
                errores.add("Tarea " + idTarea + ": " + ex.getClass().getSimpleName());
            }
        } catch (RuntimeException ex) {
            LOG.warn("Detección de atrasos: falló la creación de la alerta de la tarea {}", idTarea, ex);
            errores.add("Tarea " + idTarea + ": " + ex.getClass().getSimpleName());
        }
    }

    private boolean yaExiste(long idTarea) {
        try {
            return repositorioAlertas.findByClaveAlerta(CreadorAlertas.claveAtraso(idTarea)).isPresent();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private OffsetDateTime desde(OffsetDateTime ahora) {
        return ahora.minusHours(horasRetrospectiva);
    }
}
