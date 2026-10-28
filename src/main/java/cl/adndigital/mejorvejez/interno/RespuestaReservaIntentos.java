package cl.adndigital.mejorvejez.interno;

import java.util.List;

/**
 * Respuesta de {@code POST /api/interno/alertas/{idAlerta}/intentos/reservar}
 * (Fase 9, TSA-61): los datos de la alerta y los pares que quedaron
 * reservados en {@code PENDIENTE}. {@code intentos} viene vacía cuando el
 * origen de la alerta ya no está vigente o cuando ningún par es elegible
 * (sin canal operativo, ya enviado o con el máximo de intentos alcanzado).
 */
public record RespuestaReservaIntentos(DatosAlertaCorreo alerta, List<IntentoReservado> intentos) {
}
