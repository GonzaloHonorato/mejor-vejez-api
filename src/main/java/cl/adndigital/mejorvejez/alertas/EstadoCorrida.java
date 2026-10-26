package cl.adndigital.mejorvejez.alertas;

import cl.adndigital.mejorvejez.tareas.CodigosTareas;

/**
 * Estado final de una corrida de los procesos de alertas (regla 11 de la
 * Fase 5): {@code EXITOSA} sin errores; {@code PARCIAL} con errores y algo
 * hecho (al menos una alerta creada o un intento {@code ENVIADO}); {@code
 * FALLIDA} con errores y nada hecho. La comparten la detección de atrasos y
 * la de alertas preventivas.
 */
public final class EstadoCorrida {

    private EstadoCorrida() {
    }

    /** Código de {@code EJECUCION_PROCESO.ESTADO} según las alertas creadas, los intentos enviados y los errores. */
    public static String codigo(int creadas, int enviados, int errores) {
        if (errores == 0) {
            return CodigosTareas.ESTADO_EJECUCION_EXITOSA;
        }
        return creadas > 0 || enviados > 0 ? CodigosTareas.ESTADO_EJECUCION_PARCIAL
                : CodigosTareas.ESTADO_EJECUCION_FALLIDA;
    }
}
