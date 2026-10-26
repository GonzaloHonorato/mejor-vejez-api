package cl.adndigital.mejorvejez.notificaciones;

import java.util.List;

/**
 * Cuerpo de {@code PUT /api/yo/canales} (regla 17 de la Fase 5): reemplaza
 * por completo el conjunto de canales del usuario autenticado. {@code
 * canales} nulo, o con un elemento nulo, es un 422 ("La lista de canales no
 * es válida."); para volver al predeterminado se envía la lista vacía (solo
 * {@code CORREO} de la cuenta).
 */
public record SolicitudActualizarCanales(List<SolicitudCanal> canales) {
}
