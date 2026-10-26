package cl.adndigital.mejorvejez.alertas;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Lo que comparten las dos corridas de alertas (reglas 11 y 12 de la Fase 5):
 * {@link ServicioDeteccionAtrasos} (TSA-43) y {@link
 * ServicioAlertasPreventivas} (TSA-44). Cada servicio conserva solo lo que le
 * es propio (sus candidatas y su creación por unidad, con sus mensajes de
 * log); este colaborador cierra la corrida, registra el fallo del proceso y
 * audita la corrida manual, siempre igual entre los dos.
 *
 * <p>Ronda de corrección 1 (revisión de la Tarea 6): antes, {@code procesar},
 * el {@code catch} del proceso, {@code ejecutarManual} y {@code
 * auditarFallo} estaban duplicados casi literalmente entre los dos
 * servicios. Sin cambios de comportamiento: mismo orden de operaciones,
 * mismos parámetros a {@code RegistroEjecuciones} y {@code
 * ServicioAuditoria} que antes de la extracción.
 */
@Component
class CorridaAlertas {

    private static final String SEPARADOR_ERRORES = "\n";
    private static final String ID_ENTIDAD_SIN_EJECUCION = "SIN_EJECUCION";

    private final RegistroEjecuciones registro;
    private final ServicioAuditoria servicioAuditoria;

    CorridaAlertas(RegistroEjecuciones registro, ServicioAuditoria servicioAuditoria) {
        this.registro = registro;
        this.servicioAuditoria = servicioAuditoria;
    }

    /**
     * Cierra la corrida (regla 11) a partir de lo acumulado durante el
     * cuerpo: las alertas creadas en esta corrida, los errores de creación y
     * el resultado de "notificar pendientes". {@code CANTIDAD_ERRORES} suma
     * los errores de creación, los intentos {@code FALLIDO}, las alertas
     * creadas en ESTA corrida sin ningún destinatario con canal operativo y
     * los errores inesperados del notificador. El estado sale de {@link
     * EstadoCorrida#codigo}.
     */
    RespuestaEjecucion cerrar(long idEjecucion, Set<Long> creadasEnEstaCorrida, List<String> errores,
            ResultadoNotificacion notificacion) {
        int creadas = creadasEnEstaCorrida.size();
        int cantidadErrores = errores.size() + notificacion.fallidos();
        if (notificacion.fallidos() > 0) {
            errores.add("Notificación: " + notificacion.fallidos() + " intento(s) fallido(s)");
        }
        // Regla 11: solo las alertas creadas en ESTA corrida; las anteriores ya se reportaron.
        for (Long idAlerta : notificacion.alertasSinDestinatario()) {
            if (creadasEnEstaCorrida.contains(idAlerta)) {
                errores.add("Alerta " + idAlerta + ": sin destinatarios con canal operativo");
                cantidadErrores++;
            }
        }
        errores.addAll(notificacion.errores());
        cantidadErrores += notificacion.errores().size();

        registro.finalizar(idEjecucion, EstadoCorrida.codigo(creadas, notificacion.enviados(), cantidadErrores),
                creadas, cantidadErrores, errores.isEmpty() ? null : String.join(SEPARADOR_ERRORES, errores));
        return registro.respuesta(idEjecucion);
    }

    /**
     * Registra el fallo de algo fuera de los pasos por unidad (la consulta
     * de candidatas o el notificador entero): agrega {@code "Proceso:
     * {claseSimple}"} (nunca el mensaje crudo, que podría traer SQL; ese va
     * solo al log del llamador) e intenta cerrar la {@code
     * EJECUCION_PROCESO} como {@code FALLIDA}. Un fallo al cerrar no tapa el
     * error original: se agrega como suprimido. No relanza: el llamador
     * decide (siempre debe volver a lanzar {@code ex}).
     */
    void registrarFallo(long idEjecucion, Set<Long> creadasEnEstaCorrida, List<String> errores, RuntimeException ex) {
        errores.add("Proceso: " + ex.getClass().getSimpleName());
        try {
            registro.finalizar(idEjecucion, CodigosTareas.ESTADO_EJECUCION_FALLIDA, creadasEnEstaCorrida.size(),
                    errores.size(), String.join(SEPARADOR_ERRORES, errores));
        } catch (RuntimeException alFinalizar) {
            ex.addSuppressed(alFinalizar);
        }
    }

    /**
     * Corrida manual auditada (regla 12): {@code iniciar} registra la
     * {@code EJECUCION_PROCESO} ({@code INICIADA}) y devuelve su id; {@code
     * procesar} la procesa con ese id. Si cualquiera de los dos falla, audita
     * {@code FALLO} en transacción propia (solo el tipo del error, sin su
     * mensaje; {@value #ID_ENTIDAD_SIN_EJECUCION} si ni siquiera hubo id) y
     * relanza. Si no, audita {@code EXITO} con los contadores de la
     * respuesta. {@code accion} es {@code EJECUTAR_DETECCION_ATRASOS} o
     * {@code EJECUTAR_ALERTAS_PREVENTIVAS}.
     */
    RespuestaEjecucion ejecutarManualAuditado(Usuario administrador, String accion, Supplier<Long> iniciar,
            LongFunction<RespuestaEjecucion> procesar) {
        Long idEjecucion = null;
        RespuestaEjecucion respuesta;
        try {
            idEjecucion = iniciar.get();
            respuesta = procesar.apply(idEjecucion);
        } catch (RuntimeException ex) {
            auditarFallo(administrador, accion, idEjecucion, ex);
            throw ex;
        }
        servicioAuditoria.registrar(new EventoARegistrar(CodigosAlertas.MODULO,
                CodigosTareas.TABLA_EJECUCION_PROCESO, String.valueOf(respuesta.idEjecucion()), null,
                administrador.getIdUsuario(), accion, ResultadoAuditoria.EXITO,
                Map.<String, Object>of("idEstado", respuesta.idEstado(), "cantidadCreada", respuesta.cantidadCreada(),
                        "cantidadErrores", respuesta.cantidadErrores())));
        return respuesta;
    }

    private void auditarFallo(Usuario administrador, String accion, Long idEjecucion, RuntimeException ex) {
        try {
            servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosAlertas.MODULO,
                    CodigosTareas.TABLA_EJECUCION_PROCESO,
                    idEjecucion == null ? ID_ENTIDAD_SIN_EJECUCION : String.valueOf(idEjecucion), null,
                    administrador.getIdUsuario(), accion, ResultadoAuditoria.FALLO,
                    Map.<String, Object>of("tipoError", ex.getClass().getSimpleName())));
        } catch (RuntimeException alAuditar) {
            ex.addSuppressed(alAuditar);
        }
    }
}
