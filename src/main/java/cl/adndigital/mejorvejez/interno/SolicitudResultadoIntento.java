package cl.adndigital.mejorvejez.interno;

/**
 * Cuerpo de {@code PUT /api/interno/intentos/{idIntento}} (Fase 9, TSA-61):
 * el resultado que el microservicio de Alertas entrega tras intentar enviar
 * un par (destinatario, canal) reservado en {@code PENDIENTE}. {@code
 * resultado} es {@code ENVIADO} o {@code FALLIDO}; {@code detalleError} y
 * {@code referenciaProveedor} son opcionales y se recortan con {@code
 * TextoAcotado} a 500 y 200 bytes UTF-8 respectivamente, igual que {@link
 * cl.adndigital.mejorvejez.alertas.IntentoNotificacion}.
 */
public record SolicitudResultadoIntento(String resultado, String detalleError, String referenciaProveedor) {
}
