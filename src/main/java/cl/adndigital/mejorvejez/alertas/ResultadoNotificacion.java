package cl.adndigital.mejorvejez.alertas;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultado de notificar una o varias alertas (regla 9 a 11 de la Fase 5):
 * cuántos intentos quedaron {@code ENVIADO} y cuántos {@code FALLIDO} en
 * esta llamada, qué alertas no tuvieron ningún destinatario elegible con un
 * canal operativo y los errores inesperados ({@code Alerta {id}:
 * {claseSimple}}). Nunca incluye destinos (correos ni teléfonos).
 *
 * <p>{@code alertasSinDestinatario} no distingue si la alerta es nueva: eso
 * lo deciden los procesos que la consumen (Tareas 5 y 6), que solo reportan
 * las creadas en su propia corrida (regla 11).
 */
public record ResultadoNotificacion(int enviados, int fallidos, List<Long> alertasSinDestinatario,
        List<String> errores) {

    public ResultadoNotificacion {
        alertasSinDestinatario = List.copyOf(alertasSinDestinatario);
        errores = List.copyOf(errores);
    }

    /** Resultado sin envíos, fallos, alertas sin destinatario ni errores (p. ej. una alerta CERRADA). */
    public static ResultadoNotificacion vacio() {
        return new ResultadoNotificacion(0, 0, List.of(), List.of());
    }

    /** Resultado de una alerta que falló de forma inesperada: un solo error, sin envíos. */
    static ResultadoNotificacion conError(String error) {
        return new ResultadoNotificacion(0, 0, List.of(), List.of(error));
    }

    /** Suma de este resultado y {@code otro}, conservando el orden de las listas. */
    public ResultadoNotificacion sumar(ResultadoNotificacion otro) {
        List<Long> sinDestinatario = new ArrayList<>(alertasSinDestinatario);
        sinDestinatario.addAll(otro.alertasSinDestinatario);
        List<String> todosLosErrores = new ArrayList<>(errores);
        todosLosErrores.addAll(otro.errores);
        return new ResultadoNotificacion(enviados + otro.enviados, fallidos + otro.fallidos, sinDestinatario,
                todosLosErrores);
    }
}
