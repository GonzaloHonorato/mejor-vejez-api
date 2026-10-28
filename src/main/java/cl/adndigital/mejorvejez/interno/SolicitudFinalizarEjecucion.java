package cl.adndigital.mejorvejez.interno;

/**
 * Cuerpo de {@code PUT /api/interno/ejecuciones/{idEjecucion}} (Fase 9,
 * TSA-61): cierra la corrida con su estado final ({@code EXITOSA} | {@code
 * PARCIAL} | {@code FALLIDA}) y sus totales. Vía {@code
 * RegistroEjecuciones#finalizar}.
 */
public record SolicitudFinalizarEjecucion(String codigoEstado, int creadas, int errores, String resumen) {
}
