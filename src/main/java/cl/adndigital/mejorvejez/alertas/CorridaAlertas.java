package cl.adndigital.mejorvejez.alertas;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionServicioExterno;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Auditoría de una corrida manual del administrador sobre un proceso que
 * ahora vive en otro servicio (Fase 9): registra {@code EXITO} con el id de
 * ejecución, su {@code idEstado} vigente y los totales que trae la
 * respuesta reenviada, o {@code FALLO}, en transacción propia, si la
 * llamada no llegó a responder o si la ejecución quedó {@code FALLIDA}.
 */
@Component
class CorridaAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(CorridaAlertas.class);

    static final String ID_ENTIDAD_SIN_EJECUCION = "SIN_EJECUCION";

    private final ServicioAuditoria servicioAuditoria;
    private final RegistroEjecuciones registro;
    private final ServicioCatalogos servicioCatalogos;

    CorridaAlertas(ServicioAuditoria servicioAuditoria, RegistroEjecuciones registro,
            ServicioCatalogos servicioCatalogos) {
        this.servicioAuditoria = servicioAuditoria;
        this.registro = registro;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Reenvía la corrida con {@code reenviar} y audita el resultado. Si
     * {@code reenviar} lanza, audita {@code FALLO} (ver {@link
     * #auditarFallo}) y relanza. Si responde, el id de entidad es {@code
     * idEjecucion} (o {@value #ID_ENTIDAD_SIN_EJECUCION} si viene {@code
     * null}, nunca el texto {@code "null"}); si hay un id, se relee la
     * {@code EJECUCION_PROCESO} con {@link #idEstadoDe} y su {@code
     * idEstado} se agrega al detalle. El resultado de la auditoría es
     * {@code EXITO}, salvo que esa ejecución haya quedado {@code FALLIDA},
     * en cuyo caso es {@code FALLO} aunque la llamada misma no lanzara (el
     * proceso remoto sí falló).
     *
     * <p>Si la relectura de {@link #idEstadoDe} falla (la fila todavía no
     * existe, o cualquier otro error de la consulta), eso nunca tumba esta
     * llamada ni esconde que la corrida remota ya terminó: el detalle lleva
     * {@code idEstado} en {@code null}, queda un {@code LOG.warn} y la
     * auditoría sigue como {@code EXITO} (la relectura es solo un dato
     * adicional del detalle, nunca una condición para fallarle la respuesta
     * al administrador).
     */
    <T> T reenviarAuditado(Usuario administrador, String accion, Supplier<T> reenviar, Function<T, Long> idEjecucion,
            Function<T, Map<String, Object>> detalles) {
        T respuesta;
        try {
            respuesta = reenviar.get();
        } catch (RuntimeException ex) {
            auditarFallo(administrador, accion, null, ex);
            throw ex;
        }

        Long idEjecucionDe = idEjecucion.apply(respuesta);
        String idEntidad = idEjecucionDe == null ? ID_ENTIDAD_SIN_EJECUCION : String.valueOf(idEjecucionDe);
        Map<String, Object> detalle = new LinkedHashMap<>(detalles.apply(respuesta));
        ResultadoAuditoria resultado = ResultadoAuditoria.EXITO;
        if (idEjecucionDe != null) {
            Long idEstado = idEstadoDe(idEjecucionDe);
            detalle.put("idEstado", idEstado);
            if (idEstado != null && idEstado == idEstadoFallida()) {
                resultado = ResultadoAuditoria.FALLO;
            }
        }
        servicioAuditoria.registrar(new EventoARegistrar(CodigosAlertas.MODULO, CodigosTareas.TABLA_EJECUCION_PROCESO,
                idEntidad, null, administrador.getIdUsuario(), accion, resultado, detalle));
        return respuesta;
    }

    /**
     * El {@code idEstado} vigente de la {@code EJECUCION_PROCESO}, o {@code
     * null} si la fila todavía no existe o la relectura falla por cualquier
     * otro motivo. Nunca lanza: deja un {@code LOG.warn} y devuelve {@code
     * null}, porque a esta altura la corrida remota ya terminó y el
     * administrador no debe recibir un error por un problema de esta
     * relectura, puramente informativa para el detalle de la auditoría.
     */
    private Long idEstadoDe(long idEjecucion) {
        try {
            return registro.respuesta(idEjecucion).idEstado();
        } catch (RuntimeException ex) {
            LOG.warn("No se pudo releer la ejecución {} para agregar su idEstado a la auditoría: {}", idEjecucion,
                    ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Audita la corrida manual fallida, en transacción propia. Si todavía no
     * hay un id de ejecución (la llamada falló antes de tenerlo), el id de
     * entidad es {@value #ID_ENTIDAD_SIN_EJECUCION}. Nunca registra el
     * mensaje de la excepción (podría traer datos del servicio remoto), solo
     * su tipo; si es un error de un servicio externo ({@link
     * ExcepcionServicioExterno}), agrega también el estado HTTP que se le
     * devolvió al administrador.
     */
    private void auditarFallo(Usuario administrador, String accion, Long idEjecucion, RuntimeException ex) {
        try {
            Map<String, Object> detalle = new LinkedHashMap<>();
            detalle.put("tipoError", ex.getClass().getSimpleName());
            if (ex instanceof ExcepcionServicioExterno externo) {
                detalle.put("estadoHttp", externo.getEstado().value());
            }
            servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosAlertas.MODULO,
                    CodigosTareas.TABLA_EJECUCION_PROCESO,
                    idEjecucion == null ? ID_ENTIDAD_SIN_EJECUCION : String.valueOf(idEjecucion), null,
                    administrador.getIdUsuario(), accion, ResultadoAuditoria.FALLO, detalle));
        } catch (RuntimeException alAuditar) {
            ex.addSuppressed(alAuditar);
        }
    }

    private long idEstadoFallida() {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_EJECUCION_PROCESO, CodigosTareas.CAMPO_ESTADO,
                CodigosTareas.ESTADO_EJECUCION_FALLIDA);
    }
}
