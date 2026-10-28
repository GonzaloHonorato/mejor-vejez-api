package cl.adndigital.mejorvejez.interno;

/**
 * Resultado de una corrida de detección de atrasos o de alertas preventivas,
 * tal como lo devuelve el microservicio de Alertas (Fase 9): el mismo
 * contrato, con un record propio del Core. {@code idEjecucion} puede venir
 * {@code null} si el servicio remoto no llegó a registrar la ejecución.
 */
public record ResumenCorrida(Long idEjecucion, int creadas, int errores, String resumen) {
}
