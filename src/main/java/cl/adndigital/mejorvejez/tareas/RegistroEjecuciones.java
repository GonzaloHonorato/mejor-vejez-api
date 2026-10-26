package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;

/**
 * Registra las corridas del generador en {@code EJECUCION_PROCESO} y marca
 * las ocurrencias que fallaron. Cada operación va en su propia transacción
 * ({@code REQUIRES_NEW}): la corrida queda registrada aunque falle otra
 * parte del proceso, y una ocurrencia se marca ERROR aunque la transacción
 * que intentó crear su tarea haya hecho rollback.
 */
@Component
public class RegistroEjecuciones {

    private final RepositorioEjecucionProceso repositorioEjecuciones;
    private final RepositorioOcurrenciaPlanificada repositorioOcurrencias;
    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final RepositorioConsultaGeneracion consultaGeneracion;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;

    public RegistroEjecuciones(RepositorioEjecucionProceso repositorioEjecuciones,
            RepositorioOcurrenciaPlanificada repositorioOcurrencias, RepositorioPlanMedicamentos repositorioPlanes,
            RepositorioConsultaGeneracion consultaGeneracion, ServicioCatalogos servicioCatalogos, Clock reloj) {
        this.repositorioEjecuciones = repositorioEjecuciones;
        this.repositorioOcurrencias = repositorioOcurrencias;
        this.repositorioPlanes = repositorioPlanes;
        this.consultaGeneracion = consultaGeneracion;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
    }

    /** Registra una corrida nueva de {@code GENERACION_TAREAS}, {@code INICIADA}, y devuelve su id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long iniciar(OffsetDateTime inicio, OffsetDateTime fin) {
        EjecucionProceso ejecucion = new EjecucionProceso(CodigosTareas.TIPO_EJECUCION_GENERACION, inicio, fin,
                estadoEjecucion(CodigosTareas.ESTADO_EJECUCION_INICIADA), OffsetDateTime.now(reloj));
        return repositorioEjecuciones.save(ejecucion).getIdEjecucion();
    }

    /** Cierra la corrida con el estado ({@code EXITOSA} | {@code PARCIAL} | {@code FALLIDA}), totales y resumen. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizar(long idEjecucion, String codigoEstado, int creadas, int errores, String resumen) {
        EjecucionProceso ejecucion = repositorioEjecuciones.findById(idEjecucion).orElseThrow();
        ejecucion.finalizar(estadoEjecucion(codigoEstado), creadas, errores, resumen, OffsetDateTime.now(reloj));
        repositorioEjecuciones.save(ejecucion);
    }

    /**
     * Marca ERROR la ocurrencia cuya tarea no se pudo crear, para que la
     * siguiente corrida la reintente (regla 7). Bloquea su plan antes de
     * releerla (regla 6) y solo la toca si sigue PLANIFICADA: si mientras
     * tanto otra corrida le creó la tarea (TAREA_CREADA) o el recálculo la
     * canceló, no la pisa; si ya estaba ERROR, la deja igual.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void marcarError(long idOcurrencia) {
        Optional<Long> idPlan = consultaGeneracion.planDeOcurrencia(idOcurrencia);
        if (idPlan.isEmpty()) {
            return;
        }
        repositorioPlanes.bloquearPorId(idPlan.get());
        long idPlanificada = estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA);
        repositorioOcurrencias.findById(idOcurrencia)
                .filter(ocurrencia -> ocurrencia.getIdEstado() == idPlanificada)
                .ifPresent(ocurrencia -> {
                    ocurrencia.cambiarEstado(estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_ERROR));
                    repositorioOcurrencias.save(ocurrencia);
                });
    }

    /** La corrida tal como quedó registrada. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RespuestaEjecucion respuesta(long idEjecucion) {
        return RespuestaEjecucion.desde(repositorioEjecuciones.findById(idEjecucion).orElseThrow());
    }

    private long estadoEjecucion(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_EJECUCION_PROCESO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long estadoOcurrencia(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_OCURRENCIA_PLANIFICADA, CodigosTareas.CAMPO_ESTADO,
                codigo);
    }
}
