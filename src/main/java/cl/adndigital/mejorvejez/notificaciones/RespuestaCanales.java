package cl.adndigital.mejorvejez.notificaciones;

import java.util.List;

/**
 * Respuesta de {@code GET}/{@code PUT /api/yo/canales} (regla 17 de la Fase
 * 5). {@code canales} siempre incluye {@code CORREO} (orden: CORREO,
 * WHATSAPP); {@code WHATSAPP} solo aparece si el usuario tiene fila propia.
 * {@code whatsappDisponible} refleja si el canal WhatsApp está disponible
 * en el Core ({@code mejorvejez.notificaciones.whatsapp-habilitado}, spike
 * TSA-46, fijo en {@code false} en este MVP), para que el front pueda
 * ocultar o deshabilitar esa opción.
 */
public record RespuestaCanales(List<RespuestaCanal> canales, boolean whatsappDisponible) {
}
