package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Generador diario de tareas de medicamentos (HU-05, reglas 1, 6 y 7 de la
 * Fase 4). Una corrida:
 * <ol>
 * <li>registra la {@code EJECUCION_PROCESO} ({@code INICIADA});</li>
 * <li>fase A: por cada plan candidato, {@link GeneradorPorPlan#planificar}
 * (una transacción por plan) reserva las ocurrencias PLANIFICADA que
 * faltan;</li>
 * <li>fase B: por cada ocurrencia PLANIFICADA o ERROR de la ventana, {@link
 * CreadorTareas#crearDesdeOcurrencia} (una transacción por ocurrencia) crea
 * su tarea. Si falla, otra transacción la marca ERROR para que la siguiente
 * corrida la reintente;</li>
 * <li>cierra la corrida: sin errores EXITOSA; con errores y al menos una
 * tarea creada PARCIAL; con errores y ninguna creada FALLIDA.</li>
 * </ol>
 *
 * <p>No es {@code @Transactional}: cada paso abre la suya ({@code
 * REQUIRES_NEW}), para que un fallo en un plan o una ocurrencia no deshaga lo
 * demás. Es idempotente: reejecutarla, o correr dos a la vez, no duplica
 * tareas (cada transacción bloquea el plan y relee el estado; las claves
 * {@code UNIQUE} son la última barrera).
 */
@Service
public class ServicioGeneracionTareas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioGeneracionTareas.class);
    private static final String SEPARADOR_ERRORES = "\n";
    private static final String ID_ENTIDAD_SIN_EJECUCION = "SIN_EJECUCION";

    private final RegistroEjecuciones registro;
    private final GeneradorPorPlan generadorPorPlan;
    private final CreadorTareas creadorTareas;
    private final RepositorioConsultaGeneracion consultaGeneracion;
    private final RepositorioOcurrenciaPlanificada repositorioOcurrencias;
    private final RepositorioEjecucionProceso repositorioEjecuciones;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioAuditoria servicioAuditoria;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;
    private final int diasVentana;

    public ServicioGeneracionTareas(RegistroEjecuciones registro, GeneradorPorPlan generadorPorPlan,
            CreadorTareas creadorTareas, RepositorioConsultaGeneracion consultaGeneracion,
            RepositorioOcurrenciaPlanificada repositorioOcurrencias,
            RepositorioEjecucionProceso repositorioEjecuciones, ServicioAutorizacion servicioAutorizacion,
            ServicioAuditoria servicioAuditoria, ServicioCatalogos servicioCatalogos, Clock reloj,
            @Value("${mejorvejez.tareas.dias-ventana}") int diasVentana) {
        this.registro = registro;
        this.generadorPorPlan = generadorPorPlan;
        this.creadorTareas = creadorTareas;
        this.consultaGeneracion = consultaGeneracion;
        this.repositorioOcurrencias = repositorioOcurrencias;
        this.repositorioEjecuciones = repositorioEjecuciones;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioAuditoria = servicioAuditoria;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
        this.diasVentana = diasVentana;
    }

    /**
     * Corrida programada (00:05 America/Santiago, {@link ProgramadorProcesos})
     * sobre todos los grupos, con la ventana de la regla 1: desde las 00:00
     * de hoy hasta las 00:00 de {@code hoy + dias-ventana}.
     */
    public RespuestaEjecucion generarProgramado() {
        LocalDate hoy = LocalDate.now(reloj.withZone(ZonaNegocio.ZONA));
        return ejecutar(inicioDelDia(hoy), inicioDelDia(hoy.plusDays(diasVentana)), null);
    }

    /**
     * Corrida manual del administrador ({@code POST
     * /api/administracion/procesos/generacion-tareas}): la misma ventana que
     * la programada, sobre todos los grupos, y auditada como {@code
     * EJECUTAR_GENERACION_TAREAS}. Si la corrida falla fuera de los pasos por
     * plan u ocurrencia y relanza, igual se audita, con resultado {@code
     * FALLO} y en transacción propia (sin el mensaje del error: solo el tipo),
     * para no perder quién lanzó el proceso.
     */
    public RespuestaEjecucion generarManual() {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        LocalDate hoy = LocalDate.now(reloj.withZone(ZonaNegocio.ZONA));
        OffsetDateTime inicio = inicioDelDia(hoy);
        OffsetDateTime fin = inicioDelDia(hoy.plusDays(diasVentana));
        Long idEjecucion = null;
        RespuestaEjecucion respuesta;
        try {
            idEjecucion = registro.iniciar(inicio, fin);
            respuesta = procesar(idEjecucion, inicio, fin, null);
        } catch (RuntimeException ex) {
            auditarFallo(administrador, idEjecucion, ex);
            throw ex;
        }
        servicioAuditoria.registrar(new EventoARegistrar(CodigosTareas.MODULO, CodigosTareas.TABLA_EJECUCION_PROCESO,
                String.valueOf(respuesta.idEjecucion()), null, administrador.getIdUsuario(),
                AccionesTareas.EJECUTAR_GENERACION_TAREAS, ResultadoAuditoria.EXITO,
                Map.<String, Object>of("idEstado", respuesta.idEstado(), "cantidadCreada", respuesta.cantidadCreada(),
                        "cantidadErrores", respuesta.cantidadErrores())));
        return respuesta;
    }

    /**
     * Audita la corrida manual fallida. Si ni siquiera se pudo registrar la
     * {@code EJECUCION_PROCESO}, el id de entidad es {@value
     * #ID_ENTIDAD_SIN_EJECUCION}. Un fallo al auditar no tapa el error
     * original: se agrega como suprimido.
     */
    private void auditarFallo(Usuario administrador, Long idEjecucion, RuntimeException ex) {
        try {
            servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosTareas.MODULO,
                    CodigosTareas.TABLA_EJECUCION_PROCESO,
                    idEjecucion == null ? ID_ENTIDAD_SIN_EJECUCION : String.valueOf(idEjecucion), null,
                    administrador.getIdUsuario(), AccionesTareas.EJECUTAR_GENERACION_TAREAS, ResultadoAuditoria.FALLO,
                    Map.<String, Object>of("tipoError", ex.getClass().getSimpleName())));
        } catch (RuntimeException alAuditar) {
            ex.addSuppressed(alAuditar);
        }
    }

    /** Las últimas 20 corridas del tipo de proceso indicado, de la más reciente a la más antigua (solo administrador). */
    public List<RespuestaEjecucion> listarEjecuciones(String tipo) {
        servicioAutorizacion.exigirAdministrador();
        return repositorioEjecuciones.findTop20ByTipoEjecucionOrderByFechaInicioDescIdEjecucionDesc(tipo).stream()
                .map(RespuestaEjecucion::desde)
                .toList();
    }

    /**
     * Una corrida sobre {@code [inicio, fin)}, de todos los grupos o solo de
     * {@code soloGrupo} (las pruebas siempre lo pasan: la BD es compartida).
     * Un error en un plan o en una ocurrencia se acumula en el resumen y la
     * corrida sigue. Si falla algo fuera de esos pasos (p. ej. la consulta de
     * candidatos), intenta cerrar la corrida como FALLIDA y relanza.
     */
    RespuestaEjecucion ejecutar(OffsetDateTime inicio, OffsetDateTime fin, Long soloGrupo) {
        return procesar(registro.iniciar(inicio, fin), inicio, fin, soloGrupo);
    }

    /** Cuerpo de {@link #ejecutar} sobre una {@code EJECUCION_PROCESO} ya registrada como {@code INICIADA}. */
    private RespuestaEjecucion procesar(long idEjecucion, OffsetDateTime inicio, OffsetDateTime fin,
            Long soloGrupo) {
        List<String> errores = new ArrayList<>();
        int creadas = 0;
        try {
            LocalDate desde = inicio.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
            LocalDate hasta = fin.minusNanos(1).atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
            for (long idPlan : consultaGeneracion.planesCandidatos(desde, hasta, soloGrupo)) {
                try {
                    errores.addAll(generadorPorPlan.planificar(idPlan, inicio, fin));
                } catch (RuntimeException ex) {
                    LOG.warn("Generación de tareas: falló la planificación del plan {}", idPlan, ex);
                    errores.add("Plan " + idPlan + ": " + mensaje(ex));
                }
            }

            List<Long> porProcesar = repositorioOcurrencias.idsPorProcesar(inicio, fin,
                    List.of(estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA),
                            estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_ERROR)),
                    soloGrupo);
            for (long idOcurrencia : porProcesar) {
                try {
                    if (creadorTareas.crearDesdeOcurrencia(idOcurrencia)) {
                        creadas++;
                    }
                } catch (RuntimeException ex) {
                    LOG.warn("Generación de tareas: falló la creación de la tarea de la ocurrencia {}", idOcurrencia,
                            ex);
                    errores.add("Ocurrencia " + claveDe(idOcurrencia) + ": " + mensaje(ex));
                    marcarError(idOcurrencia);
                }
            }
        } catch (RuntimeException ex) {
            errores.add("Proceso: " + mensaje(ex));
            try {
                registro.finalizar(idEjecucion, CodigosTareas.ESTADO_EJECUCION_FALLIDA, creadas, errores.size(),
                        String.join(SEPARADOR_ERRORES, errores));
            } catch (RuntimeException alFinalizar) {
                ex.addSuppressed(alFinalizar);
            }
            throw ex;
        }

        String estado = errores.isEmpty() ? CodigosTareas.ESTADO_EJECUCION_EXITOSA
                : creadas > 0 ? CodigosTareas.ESTADO_EJECUCION_PARCIAL : CodigosTareas.ESTADO_EJECUCION_FALLIDA;
        registro.finalizar(idEjecucion, estado, creadas, errores.size(),
                errores.isEmpty() ? null : String.join(SEPARADOR_ERRORES, errores));
        return registro.respuesta(idEjecucion);
    }

    /**
     * Marca ERROR la ocurrencia que falló. Si incluso eso falla, la
     * ocurrencia queda PLANIFICADA y la próxima corrida la reintenta igual
     * (la fase B toma PLANIFICADA y ERROR): solo se registra en el log.
     */
    private void marcarError(long idOcurrencia) {
        try {
            registro.marcarError(idOcurrencia);
        } catch (RuntimeException ex) {
            LOG.error("Generación de tareas: no se pudo marcar ERROR la ocurrencia {}", idOcurrencia, ex);
        }
    }

    private String claveDe(long idOcurrencia) {
        try {
            return repositorioOcurrencias.findById(idOcurrencia)
                    .map(OcurrenciaPlanificada::getClaveOcurrencia)
                    .orElse("id " + idOcurrencia);
        } catch (RuntimeException ex) {
            return "id " + idOcurrencia;
        }
    }

    private long estadoOcurrencia(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_OCURRENCIA_PLANIFICADA, CodigosTareas.CAMPO_ESTADO,
                codigo);
    }

    private static OffsetDateTime inicioDelDia(LocalDate fecha) {
        return ZonedDateTime.of(fecha, LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private static String mensaje(RuntimeException ex) {
        String mensaje = ex.getMessage();
        return mensaje == null || mensaje.isBlank() ? ex.getClass().getSimpleName() : mensaje;
    }
}
